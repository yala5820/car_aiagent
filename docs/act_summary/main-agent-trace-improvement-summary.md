# Main Agent Trace 改造工作总结

日期：2026-07-02

## 1. 验收结论

阶段 1 到阶段 6 的 trace 改造已完成代码侧和自动化测试侧验收。当前主 Agent 的 `TEXT` 请求链路已经具备可调试的全链路 trace：入口请求、prompt 组装、LLM 调用、tool 调用、memory 提取/压缩、HTTP 错误和响应派发都已纳入统一 trace 规范。

验收结论：通过，限制条件是本轮未连接真实设备和 Phoenix 服务执行手动冒烟测试。

## 2. 当前 Trace 结构

预期 Phoenix 结构：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute          仅当 LLM 调用 tool 时出现
├── gen_ai.chat           仅当 tool 结果回填后再次调用 LLM 时出现
├── memory.extract
├── memory.compress
└── response.dispatch
```

实际代码映射：

| Span | 生成位置 | 说明 |
|------|----------|------|
| `agent.request` | `TraceManager.startAgentRequest()` | Text 请求 root span |
| `prompt.assembly` | `AgentTraceRecorder.startPromptAssembly()` | transient/chat messages、tool specs、message count |
| `gen_ai.chat` | `AgentTraceRecorder.startLlmCall()` | provider、model、token usage、output、tool calls |
| `tool.execute` | `AgentTraceRecorder.startTool()` | tool name、arguments、output、success、safety veto |
| `memory.extract` | `MemoryExtractor.extract(..., trace)` | memory prompt、LLM output、candidate count、error |
| `memory.compress` | `MemoryCompressor.compress(..., trace)` | compressed、prompt、summary、output chars、error |
| `response.dispatch` | `TraceResponseDispatcher` | response success、error type、text length |

## 3. 已完成的关键修复

- 修复 `TraceContext` 可选性，缺失或类型不匹配时不影响 AgentLoop。
- 引入 `TraceSpanNames`、`TraceAttributeKeys`、`TraceAttributeWriter`，集中 span 名称和 attribute 写入规范。
- 默认 redacted 内容采集，统一处理 user input、prompt、tool arguments、tool output、memory prompt/output。
- `TEXT` root span 写入 `request.id`、`session.id`、`source.app`、`input.type`、`user.input.length`。
- `response.dispatch` 同时写入子 span 和 root summary attribute。
- `TraceResponseDispatcher` 保证 first response wins，避免 timeout 后成功结果覆盖 root status。
- timeout 分支使用 `dispatchAndClose()`，用户收到 timeout 后 root span 会立即结束。
- `TraceSession` 使用 root context 显式创建子 span，修复跨线程 parent 丢失。
- `AgentConfig` 增加 `modelName`，LLM span 记录真实模型名。
- memory extract/compress 增加 trace 覆盖，并保留旧 overload 的 no-trace 行为。
- `TracingOkHttpInterceptor` 使用标准化 HTTP attribute，并对 IOException、4xx/5xx 标记 error。

## 4. 验证结果

已执行并通过：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests "com.hirain.aiagent.trace.*"
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```

结果：

- trace 单元测试：`BUILD SUCCESSFUL`
- Java/Kotlin 编译：`BUILD SUCCESSFUL`
- 完整 debug unit test：`BUILD SUCCESSFUL`

观察到的非阻断信息：

- `AndroidManifest.xml` 存在重复 permission warning。
- `TracingOkHttpInterceptorTest` 使用或覆盖了已过时 API，属于测试 fake OkHttp 类型带来的提示，不影响 trace 主逻辑验收。

## 5. Phoenix 手动冒烟步骤

本轮未连接真机/模拟器和 Phoenix 服务，因此未执行真实 Phoenix 冒烟。建议按以下步骤补充人工验收：

1. 在开发机启动 Phoenix，监听 `6006`。
2. 连接设备或模拟器后执行：

```powershell
adb reverse tcp:6006 tcp:6006
```

3. 启动 AIAgent。
4. 通过 Launcher 或测试 App 发送一个 `TEXT` 类型 `AgentRequest`。
5. 在 Phoenix 中确认 root span 名称为 `agent.request`。
6. 确认 root attribute 至少包含：

- `request.id`
- `session.id`
- `source.app`
- `input.type = TEXT`
- `user.input.length`
- `response.success`
- `response.text.length`

7. 确认内容类 attribute 在 redacted 模式下可见且已脱敏：

- `user.input`
- `prompt.chat_messages`
- `gen_ai.output`
- `tool.arguments`
- `tool.output`
- `memory.prompt`
- `memory.output`

8. 分别测试普通回答、tool 调用、timeout 或模拟异常路径，确认 `response.dispatch` 和 error status 表现符合预期。

## 6. 当前边界

本轮验收聚焦主 AgentLoop、memory、prompt、tool、HTTP。以下链路仍不纳入本次完成口径：

- `IMAGE` 请求和 VLM 直接调用。
- VR/TTS。
- 场景 Agent。
- Camera 抓拍链路。
- 更细粒度车辆状态机内部 span。

这些链路后续如进入正式需求，应另起阶段设计，不建议混入当前主 Agent trace 收口。

## 7. 本轮工作范围与具体内容

本轮工作不是新增一个独立的“日志模块”，而是围绕主 Agent 调试需求，把原来零散、手动、覆盖不均的 trace 改造成一套标准化的请求级追踪系统。目标是：当一个外部应用通过 AIDL 调用 `TEXT` 类型的主 Agent 请求时，可以在 Phoenix 中看到一次完整的 agent 运行过程，并能快速回答“用户输入是什么、最终 prompt 怎么组装、LLM 返回了什么、是否触发 tool、tool 参数和结果是什么、memory 是否提取或压缩、最终响应是否成功、失败发生在哪一步”。

本轮具体工作可以分为以下几类：

| 工作项 | 具体内容 | 结果 |
|--------|----------|------|
| 现状审查 | 阅读 README、agents 文档、trace 实现总结、trace 包、AgentLoop、memory、prompt、HTTP client 相关代码 | 确认原 trace 栈可用，但标准化、生命周期、覆盖面和异常链路存在缺口 |
| 安全隐患修复审查 | 检查 root span 是否会泄露、timeout 是否会绕过响应 trace、成功结果是否会覆盖 timeout 状态、跨线程 parent 是否丢失 | 推动补齐 `TraceResponseDispatcher`、`dispatchAndClose()`、root context parent 绑定等关键问题 |
| 标准化改造审查 | 检查 span name、attribute key、内容采集、脱敏、错误标记是否统一 | 落地 `TraceSpanNames`、`TraceAttributeKeys`、`TraceAttributeWriter`、`TraceRedactor` |
| 主 AgentLoop 覆盖 | 检查 prompt 组装、LLM 调用、tool 调用、tool safety veto、循环迭代等是否进入同一个 trace | 落地 `AgentTraceRecorder`，主循环 span 收敛到统一入口 |
| Memory 覆盖 | 检查 memory extract/compress 是否能记录 prompt、输出、候选数量、压缩结果和异常 | 增加带 trace 参数的 overload，同时保留旧 no-trace 调用兼容性 |
| HTTP 覆盖 | 检查 LLM HTTP 请求是否能记录方法、URL、host、耗时、状态码、IOException、4xx/5xx | `TracingOkHttpInterceptor` 记录标准 HTTP attribute，并在错误时标记当前 LLM span |
| 响应派发覆盖 | 检查最终 response 是否能进入 trace，并能处理正常、异常、timeout、多响应竞争 | `response.dispatch` span + root summary attribute，first response wins |
| 自动化验证 | 执行 trace 专项单测、Java/Kotlin 编译、完整 debug unit test | 三类验证均通过，未发现阻断问题 |
| 文档交付 | 更新验收报告，并扩充本文档为设计说明和交付总结 | 形成可用于后续维护的 trace 系统说明 |

## 8. Trace 系统设计目标

这套 trace 系统按“开发调试优先、生产默认关闭”的思路设计。当前项目是 demo 阶段，需求重点不是做大而全的可观测平台，而是在主 AgentLoop、memory、prompt 这几个核心模块中留下足够清晰的调试证据。

设计上重点解决五个问题：

1. **一次请求一棵树**：每个 AIDL `TEXT` 请求对应一个 `agent.request` root span，后续 prompt、LLM、tool、memory、response 都是它的子 span。
2. **业务含义明确**：span 名称不直接使用类名或方法名，而使用稳定的业务阶段名，例如 `prompt.assembly`、`gen_ai.chat`、`tool.execute`。
3. **内容可调试但可控**：默认开发模式记录输入、prompt、LLM 输出、tool 参数、tool 结果、memory prompt/output，但通过 redactor 脱敏并限制长度。
4. **异常链路可闭合**：正常返回、tool veto、HTTP 错误、IOException、timeout 都要有明确的 status 和 error attribute，root span 不能悬挂。
5. **业务代码少感知**：AgentLoop 只通过 `TraceContext` / `AgentTraceRecorder` 表达“现在进入哪个业务阶段”，具体 attribute 名称、脱敏、截断、span 名称统一收敛在 trace 包内。

## 9. Trace 系统结构

当前 trace 系统位于 `app/src/main/java/com/hirain/aiagent/trace/`，核心结构如下：

| 组件 | 角色 | 设计意义 |
|------|------|----------|
| `TraceConfig` | trace 配置入口 | 统一控制是否启用、OTLP endpoint、service name/version、batch 参数、内容采集模式 |
| `TraceManager` | OpenTelemetry facade | 业务层不直接接触 SDK 初始化细节，通过它创建请求级 `TraceSession` |
| `TraceSession` | 单次请求生命周期 | 持有 root span、root context、traceId、writer，负责创建子 span 和关闭 root span |
| `TraceContext` | 上下文传递对象 | 让 AgentLoop、memory 等模块能拿到当前 trace session，同时保持参数可选 |
| `TraceSpanNames` | span 名称常量 | 避免业务代码散落字符串，保证 Phoenix 中的 trace 结构稳定 |
| `TraceAttributeKeys` | attribute key 常量 | 统一字段命名，避免同一含义出现多套 key |
| `TraceAttributeWriter` | attribute 写入器 | 集中处理普通字段、文本字段、参数字段、结果字段的写入、脱敏、截断 |
| `TraceRedactor` | 内容脱敏器 | 处理手机号、邮箱、token、key、密码等敏感片段 |
| `TraceMessageFormatter` | prompt/message 格式化 | 把 LangChain4j 的 chat message、tool spec、tool request 转换成 trace 友好的字符串 |
| `AgentTraceRecorder` | 主 AgentLoop 记录器 | AgentLoop 的主要 trace API，负责 prompt、LLM、tool、memory span 的创建和补充字段 |
| `TraceResponseDispatcher` | 响应派发记录器 | 统一记录最终响应，并保证 first response wins 与 root span 正确结束 |
| `TracingOkHttpInterceptor` | HTTP 层补充记录 | 把 OkHttp 请求信息写到当前活跃 span，一般附着在 `gen_ai.chat` 上 |

这套结构的关键点是：`TraceManager` 只负责“启动一条请求 trace”，`TraceSession` 负责“这条 trace 的生命周期”，`AgentTraceRecorder` 负责“AgentLoop 业务阶段”，`TraceAttributeWriter` 负责“字段写入规范”。这样后续增加新 span 时，不需要在业务代码里重复处理脱敏、截断、字段名和错误标记。

## 10. 主 Agent Trace 树

标准 `TEXT` 请求在 Phoenix 中预期形成如下树形结构：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
│   └── HTTP attributes attached to active span
├── tool.execute                    仅当 LLM 返回 tool call 时出现，可出现多次
├── prompt.assembly                 多轮 tool 回填时，下一轮 LLM 前再次组装 prompt
├── gen_ai.chat                     多轮 tool 回填后再次调用 LLM
├── memory.extract                  需要提取长期记忆候选时出现
├── memory.compress                 session 消息超限触发压缩时出现
└── response.dispatch
```

需要注意两点：

1. `TracingOkHttpInterceptor` 当前不是单独创建 `http.client` span，而是把 HTTP attribute 写入当前活跃 span。主 LLM 调用时，当前活跃 span 应是 `gen_ai.chat`，因此 HTTP 状态码、URL、耗时和 IOException 会体现在 LLM span 上。
2. `tool.execute`、`prompt.assembly`、`gen_ai.chat` 都可能在一次请求里出现多次。原因是 AgentLoop 可能经历“LLM 决定调用工具 → 执行工具 → 工具结果回填给 LLM → LLM 生成最终回复”的多轮循环。

## 11. Span 详细说明

### 11.1 `agent.request`

`agent.request` 是一次 AIDL `TEXT` 请求的 root span，由 `TraceManager.startAgentRequest()` 创建，span kind 为 `SERVER`。

它表达的是“外部应用调用 AIAgent 后台 Service 处理一次用户请求”的完整生命周期，从请求进入 Service 开始，到最终响应派发或 timeout 结束。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `agent.persona` | 当前使用的人格或 agent 配置，例如主聊天人格 |
| `user.id` | 用户标识，便于跨请求关联同一用户 |
| `request.id` | 单次请求 ID，便于和外部调用方日志对齐 |
| `session.id` | 会话 ID，便于和 memory/session 持久化记录对齐 |
| `source.app` | 调用来源应用，例如 Launcher 或测试 App |
| `input.type` | 请求类型，本轮重点是 `TEXT` |
| `user.input.length` | 用户输入字符长度，即使内容采集关闭也可用于判断输入规模 |
| `user.input` | 用户原始输入，按配置脱敏和截断 |
| `response.success` | 最终响应是否成功，由 `TraceResponseDispatcher` 回写 |
| `response.error_type` | 最终响应错误类型，例如 timeout 或业务异常 |
| `response.text.length` | 最终响应文本长度 |

状态规则：

- 正常成功响应：root span status 为 `OK`。
- 异常响应或 timeout：root span status 为 `ERROR`，status message 使用响应错误类型或传入的状态说明。
- 多响应竞争：只接受第一次派发结果，后续成功或失败都不能覆盖 root span 状态。

### 11.2 `prompt.assembly`

`prompt.assembly` 由 `AgentTraceRecorder.startPromptAssembly()` 创建，表示一次 LLM 调用前的 prompt/message 组装结果。

它不是简单记录“系统提示词文本”，而是记录“实际送入 LLM 前的消息结构”。这对调试 agent 很关键，因为最终模型行为往往由 transient messages、历史 chat memory、工具定义共同决定。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `agent.persona` | 当前人格或 agent 配置 |
| `agent.iteration` | AgentLoop 第几轮迭代，从而区分首轮 LLM 和 tool 回填后的后续 LLM |
| `prompt.transient_messages` | 本轮临时消息，例如本次用户输入、即时上下文等 |
| `prompt.chat_messages` | 组装后的完整 chat messages，按配置脱敏和截断 |
| `prompt.message_count` | 实际输入给 LLM 的 message 数量 |
| `prompt.tool_spec_count` | 本轮暴露给 LLM 的工具数量 |
| `prompt.tool_specs` | 工具名称或工具 schema 摘要，便于判断模型看到哪些工具 |

这个 span 用来回答：

- 当前用户输入是否真的进入了 prompt？
- session 历史是否被带入？
- memory 摘要是否影响了 prompt？
- 本轮 LLM 是否看到了预期 tool？
- 多轮 tool 回填后，第二次 LLM 的 prompt 是否包含工具结果？

### 11.3 `gen_ai.chat`

`gen_ai.chat` 由 `AgentTraceRecorder.startLlmCall()` 创建，表示一次 DashScope/OpenAI-compatible Chat API 调用。

它表达的是“模型调用阶段”，包括模型名称、provider、输入消息数量、模型输出、token usage、tool call 决策，以及 HTTP 层错误信息。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `gen_ai.provider` | 固定为 `dashscope`，表示模型服务提供方 |
| `gen_ai.model` | 实际模型名，例如 `qwen-turbo`、`qwen-flash` |
| `agent.iteration` | 当前 AgentLoop 迭代轮次 |
| `prompt.message_count` | 本次模型调用输入 message 数量 |
| `gen_ai.output` | 模型文本输出，按结果字段规则脱敏和截断 |
| `gen_ai.tool_calls` | 模型要求调用的 tool 名称列表 |
| `gen_ai.usage.input_tokens` | 输入 token 数 |
| `gen_ai.usage.output_tokens` | 输出 token 数 |
| `gen_ai.usage.total_tokens` | 总 token 数 |

HTTP 相关 attribute 也会写在当前活跃 span 上：

| Attribute | 含义 |
|-----------|------|
| `http.request.method` | HTTP 方法 |
| `url.full` | 完整请求 URL |
| `server.address` | 目标 host |
| `http.duration_ms` | HTTP 请求耗时，毫秒 |
| `http.response.status_code` | HTTP 响应状态码 |
| `error.type` | IOException 类名或 HTTP 错误码 |
| `error.message` | IOException message 或 HTTP response message |

状态规则：

- 模型调用成功且 HTTP 状态码小于 400：span 保持正常。
- HTTP 4xx/5xx：span status 标记为 `ERROR`，写入 `error.type` 和 `error.message`。
- IOException：span 记录 exception，status 标记为 `ERROR`，同时仍记录请求 URL、host、method 和耗时。

### 11.4 `tool.execute`

`tool.execute` 由 `AgentTraceRecorder.startTool()` 创建，表示执行一个 LLM 发起的工具调用。

在当前项目里，tool 包含车控工具、天气工具、视觉工具等；本轮 trace 收口重点关注主 AgentLoop 中工具调用本身，而不是深入拆分每个车辆子系统内部状态变更。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `agent.iteration` | 发起该 tool call 的 AgentLoop 轮次 |
| `tool.name` | 工具名称，来自 LangChain4j `ToolExecutionRequest.name()` |
| `tool.arguments` | 工具参数 JSON 或字符串，按参数字段规则脱敏和截断 |
| `tool.output` | 工具执行结果，按结果字段规则脱敏和截断 |
| `tool.success` | 工具是否通过安全检查并完成逻辑 |
| `tool.safety_veto` | 是否被 safety guard 拦截 |
| `tool.safety_veto_reason` | 被拦截时的原因 |

这个 span 用来回答：

- 模型到底调用了哪个工具？
- 参数是不是模型生成错了？
- 工具结果是不是符合预期？
- 工具没有执行是因为业务异常，还是被 safety guard 拦截？
- 多个 tool call 的顺序和所属迭代轮次是什么？

### 11.5 `memory.extract`

`memory.extract` 由 `AgentTraceRecorder.startMemory("extract", ...)` 创建，在 `MemoryExtractor.extract(..., trace)` 中使用，表示从一段对话中提取长期记忆候选。

它表达的是“本轮对话是否产生可长期保存的信息”。这对调试用户偏好、车辆习惯、常用目的地等长期记忆尤其重要。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `memory.operation` | 固定为 `extract` |
| `memory.input_chars` | 参与提取的输入字符数 |
| `memory.prompt` | 发送给提取模型的 prompt，按文本字段规则脱敏和截断 |
| `memory.output` | 提取模型原始输出或解析前输出，按结果字段规则脱敏和截断 |
| `memory.candidate_count` | 解析出的候选记忆数量 |
| `memory.output_chars` | 输出字符数 |
| `error.type` | 提取失败时的异常类型 |
| `error.message` | 提取失败时的异常信息 |

这个 span 用来回答：

- 为什么这轮对话没有写入长期记忆？
- 提取 prompt 是否包含了足够上下文？
- 模型是否输出了候选，但解析失败？
- 候选数量是否异常偏多或偏少？

### 11.6 `memory.compress`

`memory.compress` 由 `AgentTraceRecorder.startMemory("compress", ...)` 创建，在 `MemoryCompressor.compress(..., trace)` 中使用，表示 session 历史消息达到阈值后进行摘要压缩。

它表达的是“为了控制上下文长度，对话历史是否被压缩，以及压缩结果是什么”。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `memory.operation` | 固定为 `compress` |
| `memory.input_chars` | 参与压缩的原始上下文字符数 |
| `memory.compressed` | 本次是否实际发生压缩 |
| `memory.prompt` | 发送给压缩模型的 prompt，按文本字段规则脱敏和截断 |
| `memory.output` | 压缩后的摘要结果，按结果字段规则脱敏和截断 |
| `memory.output_chars` | 摘要字符数 |
| `error.type` | 压缩失败时的异常类型 |
| `error.message` | 压缩失败时的异常信息 |

这个 span 用来回答：

- session 变长后是否触发了压缩？
- 压缩 prompt 是否正确包含历史消息？
- 压缩摘要是否丢失关键事实？
- 压缩失败后是否影响主 AgentLoop？

### 11.7 `response.dispatch`

`response.dispatch` 由 `TraceResponseDispatcher.recordResponseDispatch()` 创建，表示最终响应派发给外部调用方。

这个 span 的价值在于把“内部执行完成”和“对外返回结果”明确分开。AgentLoop 成功生成文本并不等于外部调用方一定收到成功响应；timeout、异常分支、重复回调都需要在响应派发层统一处理。

关键 attribute：

| Attribute | 含义 |
|-----------|------|
| `response.success` | 响应是否成功 |
| `response.error_type` | 响应错误类型 |
| `response.text.length` | 响应文本长度 |

同时，`TraceResponseDispatcher` 会把这些字段回写到 root `agent.request` 上，方便在 Phoenix trace 列表中不用展开子 span 就能看出本次请求是否成功、输出规模多大。

状态规则：

- `dispatch()`：记录响应并发送，但不主动关闭 root span。
- `dispatchAndClose()`：记录响应、发送响应并立即关闭 root span，主要用于 timeout 等需要立刻结束生命周期的分支。
- `AtomicBoolean responseSent` 保证 first response wins，防止 timeout 已返回后，后台晚到的成功结果覆盖 trace 状态。

### 11.8 `agent.loop`

`TraceSpanNames` 中保留了 `agent.loop` 常量，但当前主链路的重点实现不是新增一个包裹整个循环的中间 span，而是用 `agent.request` 作为请求级 root，并在其下直接挂载 prompt、LLM、tool、memory、response span。

当前这样设计更适合 demo 阶段调试：Phoenix 中展开 root 后可以直接看到关键业务阶段，层级更短，排查更快。后续如果 AgentLoop 内部阶段继续复杂化，例如引入 planner、router、多 agent 协作，再考虑启用 `agent.loop` 作为中间层。

## 12. 典型链路形态

### 12.1 普通问答

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
└── response.dispatch
```

适用于模型直接回答、不触发工具、不触发 memory 压缩的简单对话。

### 12.2 工具调用问答

```text
agent.request
├── prompt.assembly
├── gen_ai.chat                  模型返回 tool call
├── tool.execute                 执行工具
├── prompt.assembly              工具结果回填后重新组装 prompt
├── gen_ai.chat                  模型生成最终答复
└── response.dispatch
```

适用于“打开空调”“查询天气”“调节车窗”等需要 tool 的请求。调试时重点看第一段 `gen_ai.chat` 的 `gen_ai.tool_calls`、`tool.execute` 的参数和结果，以及第二段 `gen_ai.chat` 是否正确吸收工具结果。

### 12.3 Memory 提取

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
└── response.dispatch
```

适用于对话结束后需要从本轮内容提取长期记忆候选的情况。调试时重点看 `memory.prompt`、`memory.output`、`memory.candidate_count`。

### 12.4 Memory 压缩

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.compress
└── response.dispatch
```

适用于 session 历史超过阈值，需要生成摘要控制上下文长度的情况。调试时重点看 `memory.compressed` 和 `memory.output`。

### 12.5 Timeout

```text
agent.request  status=ERROR
└── response.dispatch response.success=false response.error_type=...
```

timeout 分支的关键是：外部调用方收到 timeout 后，root span 必须立刻结束。否则 Phoenix 中会出现长时间悬挂的 trace，或者后台晚到成功结果覆盖 timeout 状态。当前通过 `TraceResponseDispatcher.dispatchAndClose()` 和 first response wins 解决。

### 12.6 HTTP 异常

```text
agent.request
├── prompt.assembly
├── gen_ai.chat status=ERROR
│   ├── http.request.method
│   ├── url.full
│   ├── server.address
│   ├── http.duration_ms
│   ├── error.type
│   └── error.message
└── response.dispatch
```

HTTP IOException 或 4xx/5xx 会标记在当前 `gen_ai.chat` span 上。这样可以直接判断问题发生在模型网络调用阶段，而不是 tool 或 memory 阶段。

## 13. 内容采集与脱敏策略

当前 trace 内容采集由 `TraceConfig.ContentCaptureMode` 控制：

| 模式 | 行为 | 使用场景 |
|------|------|----------|
| `OFF` | 不写入输入、prompt、tool 参数、模型输出等内容类字段 | 生产默认模式 |
| `REDACTED` | 写入脱敏和截断后的内容 | 当前开发调试默认模式 |
| `FULL_DEBUG` | 写入截断后的原始内容 | 仅建议本地临时排查使用 |

字段按内容类型区分写入：

| Writer 方法 | 内容类型 | 默认长度限制 |
|-------------|----------|--------------|
| `putText()` | 用户输入、prompt、chat messages、memory prompt | 500 字符 |
| `putArgument()` | tool arguments | 200 字符 |
| `putResult()` | LLM output、tool output、memory output | 200 字符 |

这种设计的原因是，不同内容的调试价值和泄露风险不同：prompt 和用户输入需要更长上下文；tool 参数通常较短但可能包含目的地、账号或控制参数；模型输出和工具结果足够截取摘要即可判断问题。

## 14. 为什么仍然保留“手动业务 span”

本轮没有选择完全自动埋点，原因是 Android Agent 的关键问题不是“方法调用耗时”，而是“LLM 业务阶段发生了什么”。

自动埋点适合 HTTP、数据库、线程池这类基础设施，但它无法天然知道：

- 哪些 chat messages 是 transient，哪些来自 memory。
- 这一次 LLM 输出是最终回答，还是 tool call 决策。
- tool output 是否被 safety guard veto。
- memory extract 输出了几个候选。
- response 是正常结果、异常结果还是 timeout 结果。

因此当前方案采用“基础设施尽量自动，Agent 语义手动”的混合模式：

- HTTP 细节由 `TracingOkHttpInterceptor` 自动附着到当前 span。
- Agent 语义阶段由 `AgentTraceRecorder` 手动记录。
- attribute 名称、脱敏、截断和错误标记由 trace 包统一处理，避免业务代码各自发挥。

这比“到处直接 `span.setAttribute()`”更规范，也比“只靠自动埋点”更适合 LLM agent 调试。

## 15. 自动化测试覆盖

本轮验收中覆盖的主要测试方向包括：

| 测试方向 | 覆盖内容 |
|----------|----------|
| `AgentTraceRecorderTest` | prompt、LLM、tool、memory span 的创建和 attribute 写入 |
| `TraceSessionRootTest` | root context parent 绑定、跨线程子 span 仍挂到 root 下 |
| `TraceRedactorTest` | 用户输入、参数、结果中的敏感信息脱敏 |
| `TracingOkHttpInterceptorTest` | HTTP 成功、HTTP 错误、IOException 的 attribute 和 status |
| `AIAgentServiceTraceWiringTest` | Service 层 `TEXT` 请求 trace 创建、timeout/响应派发生命周期 |
| `MemoryExtractorTraceTest` | memory extract prompt/output/candidate/error trace |
| `MemoryCompressorTraceTest` | memory compress prompt/summary/compressed/error trace |

已经执行的验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --tests "com.hirain.aiagent.trace.*"
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```

三类命令均返回 `BUILD SUCCESSFUL`。当前剩余的验证缺口是 Phoenix 真机/模拟器手动冒烟，因为本轮没有连接真实设备和 Phoenix 服务。

## 16. 当前系统的实际调试价值

改造完成后，开发者在 Phoenix 中至少可以完成以下排查：

1. **请求是否进入主 Agent**：看是否存在 `agent.request`，以及 `request.id`、`source.app` 是否正确。
2. **输入是否正确**：看 `user.input.length` 和脱敏后的 `user.input`。
3. **prompt 是否正确组装**：看 `prompt.assembly` 中的 message count、chat messages、tool specs。
4. **模型是否返回 tool call**：看 `gen_ai.chat` 的 `gen_ai.tool_calls`。
5. **tool 参数是否正确**：看 `tool.execute` 的 `tool.name` 和 `tool.arguments`。
6. **tool 执行是否成功**：看 `tool.success`、`tool.output`、`tool.safety_veto`。
7. **模型最终回答是否正确**：看最后一次 `gen_ai.chat` 的 `gen_ai.output`。
8. **memory 是否影响行为**：看 `memory.extract` 和 `memory.compress` 的 prompt/output。
9. **失败发生在哪一层**：看哪个 span status 为 `ERROR`，以及 `error.type`、`error.message`。
10. **外部调用方最终拿到什么结果**：看 `response.dispatch` 和 root 上的 response summary。

## 17. 当前边界与后续建议

当前 trace 已经满足 demo 阶段主 Agent 调试需求，但不建议把它误认为完整量产可观测体系。后续如果需求升级，可以按以下方向逐步扩展：

| 后续方向 | 建议 |
|----------|------|
| Phoenix 手动验收 | 在真机或模拟器上跑一次普通问答、tool 调用、timeout、HTTP 错误四类 case，截图归档 |
| `IMAGE` / VLM | 单独设计 `vlm.chat` 或复用 `gen_ai.chat` 并增加 image metadata，不要混入当前 TEXT 验收口径 |
| 场景 Agent | 如果重新纳入需求，应增加 scene-specific attribute，例如 scene id、trigger source、confidence |
| VR/TTS | 可后续增加 `vr.input`、`tts.output` 等 span，但当前不属于主 AgentLoop |
| 车辆状态机内部 | 现阶段 `tool.execute` 已足够定位 tool 参数和结果；若车辆状态机复杂化，再增加 `vehicle.state_transition` |
| 生产策略 | 生产默认 `OFF` 是合理的；若将来需要线上采样，应增加采样率、用户授权、数据留存策略 |

## 18. 总结

本轮最终落地的是一套围绕 `agent.request` 的请求级 trace 系统。它把主 Agent 的关键调试信息按业务阶段分成 `prompt.assembly`、`gen_ai.chat`、`tool.execute`、`memory.extract`、`memory.compress`、`response.dispatch` 六类核心子 span，并通过统一的 span 名称、attribute key、内容脱敏、错误标记和响应派发生命周期管理，解决了原先 trace 分散、覆盖不足、异常链路不闭合、timeout 状态可能被覆盖、跨线程 parent 可能丢失等问题。

以当前项目定位看，这个方案比单纯自动埋点更适合 LLM Agent demo：它不会过度侵入业务，也能把 prompt、模型输出、tool 参数、tool 结果、memory prompt/output 这些最影响 Agent 行为的内容暴露出来，足够支撑后续主 Agent 调试和问题复盘。
