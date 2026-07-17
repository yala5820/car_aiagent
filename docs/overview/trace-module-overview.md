# AIAgent Trace 模块概览

**更新日期：** 2026-07-15

**范围：** 以当前生产 TEXT 请求链路为准；VOICE、IMAGE、CONTROL 等兼容链路不作为本阶段重点。
**源码依据：** `trace/`、`context/ContextTraceRecorder.java`、`context/ContextOrchestrator.java`、`runtime/AgentRuntime.java`、`core/TextAgentLoopOrchestrator.java`、`memory/`、`AIAgentService.kt`。

---

## 1. 模块定位

Trace 模块是 AIAgent 的请求级可观测性基础设施。它不参与意图决策、上下文拼接或工具执行，只负责把这些真实业务过程记录为同一条 OpenTelemetry Trace，并经 OTLP 导出到 Phoenix。

当前 TEXT 链路的关键目标已经落地：

- 每个 TEXT 请求以 `agent.request` 作为唯一根 span；请求、会话、人格、来源、输入、deadline、意图和 ToolGroup 等元数据附着在根节点。
- `agent.loop`、每轮 `agent.iteration`、Context、LLM、工具及响应回调使用同一个 `TraceSession` 与显式父 Context，构成可检索的树，而不是彼此独立的 span。
- Context 不再把所有输入仅压成一个巨大字符串。每个 Provider 有独立 span，进入模型的文本片段、消息贡献和工具 schema 各有独立 span。
- 工具路径按“工具请求 -> 安全检查 -> 反射调度 -> 结果写回”分阶段记录；一个模型工具调用对应一个 `tool.execute`。

Trace 的职责是回答“系统实际做了什么”，而不是证明模型文本说了什么。因此，工具已提供给模型但模型未返回 Tool Call 时，Trace 会显示 `gen_ai.request.tool_count > 0`，但不会伪造 `gen_ai.tool_calls` 或 `tool.execute`。

---

## 2. 技术栈与运行方式

| 层级 | 当前实现 | 作用 |
|---|---|---|
| Trace 标准 | OpenTelemetry API / SDK 1.48.0 | Span、Context、状态、异常、资源属性和批量处理 |
| 导出协议 | OTLP/HTTP | Android 端将 span 批量发送到 `/v1/traces` |
| 观测后端 | Phoenix | 开发调试时查看 Trace 树、属性、耗时与错误 |
| 网络诊断 | OkHttp 4.12 拦截器 | 将 HTTP 方法、地址、状态码、耗时和 IOException 写入当前活跃 span |
| AI 原语 | LangChain4j 1.16.3 | 提供 ChatMessage、ToolSpecification、ToolExecutionRequest 等需要记录的业务对象 |

`AIAgentService.onCreate()` 当前使用 `TraceConfig.development(BuildConfig.VERSION_NAME)` 初始化 `TraceManager`：启用 OTLP、服务名为 `aiagent-android`、批大小 64、批间隔 2 秒、端点为 `http://localhost:6006/v1/traces`。设备连接本机 Phoenix 时需要执行 `adb reverse tcp:6006 tcp:6006`。

生产预设 `TraceConfig.production()` 为全局 no-op：不初始化导出器、不写内容属性。Trace 初始化、导出或关闭失败均被捕获，不应阻塞 Agent 正常业务。

---

## 3. 设计与实际作用

### 3.1 生命周期与上下文传播

1. Service 为每个 TEXT 请求创建 `TraceSession`，根 span 为 `agent.request`，并在 TEXT 工作线程中 `makeCurrent()`。
2. `AgentRuntime` 从 `RequestSession` 获取 `TraceContext`，创建并激活 `agent.loop`。同一对象同时传给 Context 和 TEXT AgentLoop。
3. `ContextOrchestrator` 在 Provider 调用前创建并激活 Provider span，在 `finally` 中关闭 scope；因此 Provider span 的原生 duration 与 `provider.duration_ms` 都覆盖真实调用，而非事后补记。
4. `TextAgentLoopOrchestrator` 为每轮创建 `agent.iteration`，在此 scope 内创建 Context 装配、LLM 与工具 span。
5. `TraceResponseDispatcher` 以原子开关保证只有第一个终态响应能写入根 span、创建 `response.dispatch` 并关闭 TraceSession。

`TraceContext` 通过 `RequestSession` 和 `AgentLoopContext.extraContext` 中的 `_trace_context` 传递，避免业务层直接依赖全局 OTel SDK。子 span 均支持传入显式 parent；这消除了早期“根 span 与后续 span 断树”的问题。

### 3.2 Context 的可解释记录

Context 分两阶段运行：

- `prepare`：每请求一次，执行 8 个请求级静态 Provider：`RuntimeContextProvider`、`PersonaContextProvider`、`PromptContextProvider`、`UserInputContextProvider`、`IntentContextProvider`、`ToolGroupContextProvider`、`LongTermMemoryContextProvider`、`CallerExtraContextProvider`。
- `assemble`：每轮一次，执行 3 个迭代级动态 Provider：`SessionMemoryContextProvider`、`VehicleStateContextProvider`、`TimeContextProvider`，之后由 `ContextMessageAssembler` 处理消息顺序、预算、裁剪和必要时的压缩恢复。

每个 Provider 均有 `context.provider.<ProviderName>` span，记录生命周期、是否必需、状态、耗时、贡献数、错误信息及是否实际产出非空的模型可见内容。完成装配后，仅对真正进入模型的 Contribution 再创建下列细粒度 span：

- 文本贡献：`context.fragment.<sourceKey>`，记录 Provider、目标区域、完整片段和 `fragment.included_in_model`。
- 消息贡献：`context.message.<messageSource>`，记录 Provider、角色分布、消息数量和完整消息正文。
- 工具贡献：`context.toolset`，记录 Provider、工具数量、工具名，以及 name/description/parameters 三部分 schema。

这使排障可以从“某个 Provider 是否成功”一直追到“它的哪段内容是否真的进入这一次模型请求”。

### 3.3 LLM、工具、记忆与网络记录

- LLM 请求：`gen_ai.chat` 写入 DashScope、模型名、迭代号、最终消息、完整工具 schema、估算/服务端 token、输出文本和实际 Tool Call。
- 工具执行：仅当 `AiMessage.hasToolExecutionRequests()` 为 true 时，逐个 Tool Call 创建 `tool.execute`，并激活为当前 Context，使内部阶段与可能的下游调用挂在正确父节点下。
- 安全与反射调度：`tool.safety_check` 记录 ALLOW/DENY/REQUIRE_CONFIRMATION 与原因；通过安全检查才有 `tool.dispatch`，其中记录真实解析、反射调用、目标类/方法、耗时和成功状态。
- 工具结果写回：`tool.result_writeback` 记录 ChatMemory 与循环上下文是否均成功写入。工具根 span 综合安全决定与真实 `ToolDispatchOutcome` 写入 `tool.success`、`tool.outcome`、结果或失败原因。
- 记忆：长会话压缩产生 `memory.compress`，长期记忆提取产生 `memory.extract`；记录输入长度、提示词、输出、候选数量、压缩是否成功和异常。
- HTTP：`TracingOkHttpInterceptor` 不额外产生 HTTP span，而是在当前活跃 span（正常 TEXT LLM 请求为 `gen_ai.chat`）上补充 `http.request.method`、`url.full`、`server.address`、`http.response.status_code`、`http.duration_ms` 与错误信息。

---

## 4. 当前 TEXT Trace 树

下图是一次普通 TEXT 请求的完整结构。中括号内容表示条件节点，`N` 表示可重复次数。

```text
agent.request
├── agent.loop
│   ├── context.prepare                                      (每请求一次)
│   │   ├── context.provider.RuntimeContextProvider
│   │   ├── context.provider.PersonaContextProvider
│   │   ├── context.provider.PromptContextProvider
│   │   ├── context.provider.UserInputContextProvider
│   │   ├── context.provider.IntentContextProvider
│   │   ├── context.provider.ToolGroupContextProvider
│   │   ├── context.provider.LongTermMemoryContextProvider
│   │   └── context.provider.CallerExtraContextProvider
│   ├── memory.compress                                    [仅需要压缩时]
│   ├── memory.extract                                     [仅执行长期记忆提取时]
│   └── agent.iteration                                    (第 0..N 轮)
│       ├── context.assemble
│       │   ├── context.provider.SessionMemoryContextProvider
│       │   ├── context.provider.VehicleStateContextProvider
│       │   ├── context.provider.TimeContextProvider
│       │   ├── context.fragment.<sourceKey>                [每个入模文本贡献]
│       │   ├── context.message.<messageSource>             [每个入模消息贡献]
│       │   └── context.toolset                             [有入模工具时]
│       └── gen_ai.chat
│           └── tool.execute                                [模型返回每个 Tool Call]
│               ├── tool.safety_check
│               ├── tool.dispatch                           [安全允许且实际分发时]
│               └── tool.result_writeback
└── response.dispatch
```

树中 `response.dispatch` 是根节点的直接子节点，表示对 AIDL Listener 的终态响应，而不是某一轮 AgentLoop 的业务步骤。`memory.compress` 与 `memory.extract` 被有意固定为 `agent.loop` 的子节点，避免在多轮循环中被误解为某一轮模型主调用的子操作。

### 4.1 Span 语义总表

| Span | 创建条件 | 主要含义与关键属性 |
|---|---|---|
| `agent.request` | 每个 TEXT 请求 | 服务器根 span；request/session/user/persona/source/input、准入、deadline、意图、ToolGroup、最终响应状态 |
| `agent.loop` | Runtime 开始执行 | TEXT Agent 主循环容器；Context prepare、记忆与所有 iteration 的共同父节点 |
| `agent.iteration` | 每次模型循环 | 第几轮模型决策；工具结果回填后通常进入下一轮 |
| `context.prepare` | 每个请求 | 静态 Context 收集，含 Provider 成功/失败/回退数与贡献数 |
| `context.assemble` | 每轮 | 动态 Context 收集、消息装配、预算和压缩恢复结果 |
| `context.provider.<name>` | 每次 Provider 调用 | 真实 Provider 耗时、状态、错误、贡献数与是否有非空入模内容 |
| `context.fragment.<sourceKey>` | 非空且模型可见的文本贡献 | 某 Provider 的具体文本、目标区域和最终入模标记 |
| `context.message.<source>` | 模型可见消息贡献 | SessionMemory、当前用户等消息的来源、角色、数量与完整正文 |
| `context.toolset` | 模型可见工具贡献 | 最终提供给模型的工具名称、数量与完整 schema |
| `gen_ai.chat` | 每次主模型调用 | 最终 messages、tool specs、token、模型输出、Tool Call 和 HTTP 诊断 |
| `tool.execute` | 模型返回一个真实 ToolExecutionRequest | 单个工具的参数、总体安全/执行结果和工具输出 |
| `tool.safety_check` | 每个模型 Tool Call | 安全审核决定与原因；批量确认前也会存在 |
| `tool.dispatch` | 审核允许且进入 ToolRegistry | 真实反射分发结果、目标类/方法、参数解析和调用结果 |
| `tool.result_writeback` | 工具结果写入阶段 | 是否成功同时写回 ChatMemory 和 AgentLoopContext |
| `memory.compress` | 历史超预算并尝试压缩 | 压缩输入、提示词、摘要、是否完成和异常 |
| `memory.extract` | 执行长期记忆提取 | 提取输入、提示词、候选数、输出和异常 |
| `response.dispatch` | 首个终态响应 | AIDL 回调的成功状态、文本长度和错误类型 |
| `prompt.assembly` | 旧兼容 `AgentLoopOrchestrator` 路径 | 历史 Prompt 拼接 span；不是当前 TEXT Context 独占链路的常规节点 |

### 4.2 如何解读 `tool.execute` 缺失

以下三个字段必须联动判断：

| 观察到的现象 | 可以确认的事实 | 不能据此得出的结论 |
|---|---|---|
| `gen_ai.request.tool_count > 0` | 本轮请求把工具 schema 提供给了模型 | 工具已经被模型调用或业务已经执行 |
| 缺少 `gen_ai.tool_calls` | LLM 响应未携带 ToolExecutionRequest | 不能仅凭模型自然语言“已执行”认定执行成功 |
| 缺少 `tool.execute` | TEXT 主循环没有进入 `hasToolExecutionRequests()` 分支 | 不是 Trace 自动丢了一个工具执行 span |

因此，`tool.execute` 是执行事实的观测结果，而不是“工具可用”的展示节点。若控制意图下模型输出了“已完成”但没有 `gen_ai.tool_calls`，根因是业务链路接受了无工具调用的自然语言成功答复；这是工具调用完整性约束问题，不是 Trace 树缺失问题。

---

## 5. 文件职责

### 5.1 `trace/` 目录

| 文件 | 作用 |
|---|---|
| `TraceManager.java` | OTel SDK、Resource、OTLP HTTP Exporter 与 BatchSpanProcessor 的唯一装配入口；创建根 TraceSession，故障降级 no-op，Service 销毁时 flush/shutdown。 |
| `TraceSession.java` | 单请求 Trace 生命周期；保存根 span、root Context、traceId 和 AttributeWriter，提供显式 parent 的子 span 工厂并保证根 span 只结束一次。 |
| `TraceContext.java` | 轻量传递对象；把 TraceSession 放进 Request/Loop 的 extraContext，避免业务组件直接持有 TraceManager。 |
| `TraceConfig.java` | 集中定义启用状态、OTLP 地址、批量参数、内容采集模式与 development/production 预设。 |
| `TraceSpanNames.java` | 所有稳定业务 span 名称常量，保证测试、Phoenix 查询和业务代码一致。 |
| `TraceAttributeKeys.java` | 请求、LLM、工具、记忆、HTTP、Context、响应等属性 key 的统一词表。 |
| `TraceAttributeWriter.java` | 内容类属性的唯一策略入口；根据 `OFF`、`REDACTED`、`FULL_DEBUG` 决定不写、脱敏写入或完整写入。 |
| `TraceRedactor.java` | REDACTED 模式的数据保护：手机号掩码、长文本截断、Base64 参数识别与截断。 |
| `TraceMessageFormatter.java` | 把 LangChain4j 消息和工具对象转为可读属性文本；不自行截断，长度策略交由 AttributeWriter。 |
| `AgentTraceRecorder.java` | AgentLoop 专用记录器；创建 iteration/LLM/tool/memory span，写入 LLM 请求响应、工具阶段、异常、token 和结果属性。 |
| `TraceResponseDispatcher.java` | 把首个对外 AgentResponse 写为根状态和 `response.dispatch`，通过原子开关避免超时、成功、取消等竞争覆盖结果。 |
| `TracingOkHttpInterceptor.java` | 在当前录制 span 上附加 DashScope HTTP 请求、响应和 IOException 诊断；不创建独立 HTTP span。 |

### 5.2 与 Trace 直接协作的非 `trace/` 文件

| 文件 | 作用 |
|---|---|
| `context/ContextTraceRecorder.java` | Context 专用适配器：创建 prepare/assemble/provider span，记录模型可见 fragment/message/toolset。 |
| `context/ContextOrchestrator.java` | Provider 真实调用的包裹点；在调用前建 span + makeCurrent，在成功、异常和 finally 路径中完整结束。 |
| `runtime/AgentRuntime.java` | 由 RequestSession 的 TraceContext 创建 `agent.loop`，并把意图、ToolGroup 和请求元信息写入根 span。 |
| `core/TextAgentLoopOrchestrator.java` | 当前 TEXT 的 iteration、LLM、工具安全/调度/写回及工具结果闭环记录点。 |
| `memory/MemoryCompressor.java` | 触发并结束 `memory.compress`，记录摘要压缩过程。 |
| `memory/MemoryExtractor.java` | 触发并结束 `memory.extract`，记录长期记忆候选提取过程。 |
| `AIAgentService.kt` | 初始化/关闭 TraceManager；创建请求根 span、绑定工作线程 scope，并通过 TraceResponseDispatcher 结束请求。 |

### 5.3 Debug Eval 的关联边界

Debug Eval 复用既有请求根 span，不新增平行的 `eval.correlationId`：电脑端 case 的关联值由 TestApp 写入 `clientMessageId`，AIAgent 在根 span 写入 `client_message.id`。同时写入 `eval.environment.active` 与请求准入时的 `eval.environment.revision`，用于把状态快照和该请求开始时的环境关联起来。AgentResponse 仍是调用方结果事实，结构化状态快照由 Debug 环境接口提供；Trace 只保存观测证据，绝不写入 leaseToken、Prompt 或外层评测结果。

该能力只存在于 Debug Eval 路径。当前开发配置为 `FULL_DEBUG`，Phoenix 可能保留对话与工具内容，设备验收时必须在受控环境使用。

---

## 6. 内容采集与数据边界

| 模式 | 行为 | 适用场景 |
|---|---|---|
| `OFF` | 不写正文、参数、结果等内容属性 | 生产默认或隐私敏感环境 |
| `REDACTED` | 脱敏手机号、截断长文本和 Base64 | 可以排障但不应保存完整会话内容的环境 |
| `FULL_DEBUG` | 保留完整 Context、消息、工具 schema、参数和结果 | 当前 Demo 开发调试，便于还原模型真实输入 |

当前 `development()` 使用 `FULL_DEBUG`，且 `TraceAttributeWriter` 在该模式不截断正文。这满足 Context/工具定位的需求，但 Phoenix 收集端会含用户对话、会话历史、车辆状态和工具参数。该配置只能用于受控开发环境，不能直接作为量产默认值。

---

## 7. 当前完成度与合理性评估

| 维度 | 评估 | 依据 |
|---|---|---|
| Trace 树与父子关系 | 90% | TEXT 根、loop、iteration、Context、LLM、工具和响应均使用 TraceSession/显式 parent；单元测试已断言 parentSpanId。 |
| Context 输入可解释性 | 95% | 11 个 Provider 各自独立 span；进入模型的片段、消息与工具 schema 均可查看。 |
| LLM 请求/响应可还原性 | 90% | 最终 messages、完整工具 schema、输出、token 和 Tool Call 已记录；服务端 usage 缺失时会标记可用性。 |
| 工具执行诊断 | 90% | 具备单工具根 span、Safety、Dispatch、Writeback 和结构化 DispatchOutcome；多工具/第二轮结构有单测。 |
| 记忆过程可见性 | 80% | 压缩和提取均有 span、内容和异常；其内部模型调用尚未细分为独立 LLM 子 span。 |
| 错误与终态一致性 | 85% | LLM/记忆/HTTP 异常与首响应竞争均已处理；仍需设备侧覆盖取消、超时、导出失败。 |
| 隐私与发布治理 | 70% | 有 OFF/REDACTED/FULL_DEBUG 三档，但 Service 当前硬编码 development，且 FULL_DEBUG 保存完整业务正文。 |
| 设备侧最终验收 | 60% | 单测覆盖树形与关键属性，但尚不能替代真实设备经 `adb reverse` 导出 Phoenix 的端到端证据。 |

总体判断：当前设计是合理的，特别是 Provider 级独立 span 没有造成无意义的层级膨胀。Provider 数量固定为 11，单轮额外 span 与消息/片段数量线性相关；在一个 TEXT 请求通常只有少量迭代的前提下，定位收益明显大于导出开销。真正需要控制的是长会话消息数量、工具并发数和 FULL_DEBUG 内容体积，而不是取消 Provider span。

现阶段已能够完整回答大部分排障问题：请求接收了什么、路由选了什么、哪个 Provider 失败或未入模、模型实际收到了哪些消息和 schema、模型是否发出 Tool Call、工具是否通过安全/调度/写回、以及最终响应为何成功或失败。

---

## 8. 后续改进建议

### P1：补齐工具调用完整性业务约束

对于明确的车控等工具型意图，`tool_count > 0` 且模型未返回 Tool Call 时，不应接受“已执行”自然语言作为成功结果。应在 TEXT AgentLoop 中将此情况显式标记为“需要工具但未调用”，写入根/LLM 属性并返回澄清或失败。这项改动提升的是执行可信性；Trace 已能准确暴露该问题。

### P1：给记忆内部模型调用建立独立子 span

`memory.compress` 和 `memory.extract` 已包裹其业务过程，但内部 Summary/Extraction 模型调用没有以该 memory span 作为当前 Context 的 `gen_ai.chat` 子节点。建议后续在调用前 `makeCurrent()`，并建立带 operation 标识的独立 LLM span，使 HTTP 耗时和模型 token 能明确归属到记忆操作。

### P2：将开发/生产 Trace 配置外部化

当前 Service 直接使用 `TraceConfig.development()`。建议由 BuildConfig、产品开关或受控调试命令决定模式，并明确 Phoenix 收集端的访问控制和保留周期；发布构建默认 OFF 或 REDACTED，禁止不受控的 FULL_DEBUG。

### P2：增加按预算降采样的内容记录

固定 Provider span 应保留，但在超长历史或高频场景下，可对 `context.message.*` 正文和大工具结果设置“仅记录摘要 + 长度 + hash”的降采样策略，同时保留 `fragment.included_in_model`、来源和 token 信息。这样可控制 OTLP payload，不损失结构化定位能力。

### P2：完成真实设备验收闭环

用同一套可复现脚本覆盖至少四类请求：纯对话、单工具成功、工具安全拒绝/确认、长历史压缩。验收时检查 Phoenix 中的 parentSpanId、内容模式、HTTP 属性、取消/超时终态以及 `tool.execute` 只在真实 Tool Call 时出现。

---

## 9. 已有测试覆盖与边界

现有 JVM 测试已覆盖根子关系、Service 首响应胜出、Provider span/耗时/Context schema/完整消息、工具三阶段、多工具和“工具后下一轮模型调用”等关键结构。代表性测试包括：

- `trace/TraceSessionParentChildTest.java`
- `trace/AIAgentServiceTraceWiringTest.java`
- `trace/ContextProductionTraceHierarchyTest.java`
- `trace/ContextProviderTraceTest.java`
- `trace/ToolPhaseTraceTest.java`

这些测试证明内存导出器中的 span 结构和属性符合预期；它们不证明 Android 真机、Phoenix 服务、`adb reverse`、网络错误和实际 DashScope 响应在本次构建中均已验证。因此，设备侧 Trace 截图或导出结果仍是发布前的必要证据。
