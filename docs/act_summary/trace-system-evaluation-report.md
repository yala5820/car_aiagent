# AIAgent Trace 系统评价报告

日期：2026-07-02

## 0. 最终验收状态（2026-07-02）

本报告最初用于记录 trace 改造前的评估问题。后续已按照阶段计划完成主 Agent trace 标准化、安全修复、memory trace 覆盖、HTTP/error 规范化和验证文档更新。

当前 `TEXT` 主 Agent trace 已标准化为：

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

当前验收结论：

| 项目 | 状态 |
|------|------|
| Trace 安全策略与脱敏 | 已完成，内容类 attribute 通过 `TraceAttributeWriter` 写入 |
| Text root trace | 已完成，`agent.request` 写入 request/session/source/input 元数据 |
| Prompt / LLM / Tool trace | 已完成，由 `AgentTraceRecorder` 统一收敛 |
| Memory trace | 已完成，覆盖 extract / compress 的成功、跳过、异常路径 |
| HTTP/error 规范化 | 已完成，覆盖标准 HTTP attribute、IOException、4xx/5xx |
| 跨线程 parent 关系 | 已修复，`TraceSession` 显式使用 root context 作为子 span parent |
| timeout root span 关闭 | 已修复，timeout 响应通过 `dispatchAndClose()` 立即结束 root span |
| 自动化验证 | 已通过，见 `main-agent-trace-improvement-summary.md` |
| Phoenix 真机冒烟 | 未在本轮执行；已提供手动验收步骤 |

仍需明确的边界：

- 本轮 trace 改造聚焦 `TEXT` 主 AgentLoop、memory、prompt、tool、HTTP；`IMAGE`、VR/TTS、场景 Agent、VLM 直接调用不纳入当前验收范围。
- `agent.loop` 目前仅作为预留常量，没有生成独立 span；当前计划中的 Phoenix 结构也没有要求该 span。
- AndroidManifest 中仍有重复 permission warning，属于既有清理项，与 trace 验收无直接冲突。

## 1. 历史评价摘要（改造前）

以下内容保留为改造前问题记录。当前验收状态以第 0 节和 `main-agent-trace-improvement-summary.md` 为准。

当前 Trace 系统的方向是合理的：以 OpenTelemetry Java SDK 作为底层采集能力，用 `TraceManager` / `TraceSession` 做业务封装，在 `AgentLoopOrchestrator` 中围绕 LLM 调用和 Tool 调用创建子 span，这个设计适合 AIAgent 这种“后台 Service + Agent 主循环 + 多工具调用”的系统。

但当前实现还不能称为完整的全链路追踪。它主要覆盖了 `TEXT` 和 `VOICE` 两类请求进入 `chatOrchestrator` 后的 LLM/tool 主干；`IMAGE`、场景识别、VLM 直接调用、记忆压缩/提取、控制指令、Camera/VR/TTS 等关键链路没有进入统一 trace。更重要的是，`AgentLoopOrchestrator` 默认 `extraContext != null` 时一定存在 `_trace_context`，这会让无 trace 的场景 Agent 调用存在空指针风险。

综合评价：

| 维度 | 评价 |
|------|------|
| 技术栈选择 | 基本合理，适合当前 Demo/开发调试阶段 |
| 架构封装 | 方向合理，Facade 封装降低业务层耦合 |
| 埋点方式 | 手动埋点是必要的，但当前应进一步集中化 |
| 覆盖范围 | 中等偏低，主对话链路覆盖较好，视觉/场景/记忆链路不足 |
| 生产可用性 | 暂不充分，脱敏、开关、endpoint、导出失败策略需要收敛 |
| 文档一致性 | 部分不一致，文档说 image 已接入 trace，但当前代码未体现 |

建议将当前完成度从 README 中的“90%”下调为“60%-70%”：核心骨架完成，但覆盖和生产约束还没有闭环。

## 2. 当前实现现状

### 2.1 已完成内容

当前已有以下基础能力：

- `TraceConfig` 提供 development / production 配置。
- `TraceManager` 初始化 OpenTelemetry SDK，配置 `Resource(service.name/service.version)`、`BatchSpanProcessor` 和 OTLP HTTP exporter。
- `TraceSession` 管理一次请求的 root span，并提供 `startLlmSpan()` / `startToolSpan()`。
- `TracingOkHttpInterceptor` 能把 HTTP 状态码、URL、方法、耗时写入当前 span。
- `AgentLoopOrchestrator` 在 LLM 调用处创建 `llm.call` span，在 Tool 调用处创建 `tool.execute` span。
- `handleTextRequest()` 和 `handleVoiceRequest()` 会创建 root session，并通过 `extraContext` 传给 `AgentLoopOrchestrator`。

这些能力能支撑最核心的文本对话 trace：AIDL 请求 -> root span -> LLM span -> tool span -> LLM span -> 响应。

### 2.2 当前未覆盖或覆盖不足

以下链路没有完整 trace：

| 链路 | 当前情况 | 风险 |
|------|----------|------|
| `IMAGE` 请求 | `handleImageRequest()` 没有创建 `TraceSession` | 图像问答不可观测 |
| `VlManager.frontCameraInteractionPositive()` | VLM 模型直接调用，没有 interceptor，没有 span | VLM 延迟、错误、输出不可追踪 |
| `SceneMatch.vl_scene_match()` | 场景识别 VLM 直接调用，没有 interceptor，没有 span | 主动场景服务不可观测 |
| 场景 Agent | `sceneOrchestrator.execute("", mapOf("scene" to scene))` 未传 trace context | 可能触发空指针，并且没有 trace |
| 记忆压缩/提取 | `MemoryCompressor` / `MemoryExtractor` 调用模型没有 span | 隐性 LLM 消耗和失败不可观测 |
| `CONTROL` 请求 | `handleControlRequest()` 没有 trace | StartListen/StopListen/ClearMemory 行为不可审计 |
| Camera 抓拍 | 只有 Log，无 span/metric | 场景识别链路无法定位瓶颈 |
| VR/TTS | 只有 Log，无 span/metric | 语音播报失败和耗时不可观测 |
| Tool 内部状态机 | 只有 `tool.execute` 粗粒度 span | 无法区分参数校验、状态变更、拒绝原因 |

## 3. 技术栈设置评价

### 3.1 OpenTelemetry Java SDK 是否合理

合理，但要明确它解决的是“业务 trace 采集”，不是完整 Android RUM。

当前项目是纯后台 Service，无 UI，无 Activity 交互体验指标需求；核心问题是一次 Agent 请求内部经历了哪些 LLM 调用、工具调用、状态变更、耗时和失败。因此使用 OpenTelemetry Java SDK 手动创建业务 span 是合理的。

官方 Android Agent 更偏 Android RUM，提供 Activity、Fragment、ANR、Crash、网络状态、启动、session、慢帧等自动采集能力。AIAgent 没有 UI，这些能力不是当前优先级。Java zero-code agent 则更适合普通 JVM 服务端进程，不适合 Android APK 直接照搬。

所以当前不需要因为“手动埋点”就整体切换到 Android Agent 或 Java Agent。更合理的方向是：保留当前 OpenTelemetry Java SDK + 业务手动 span，同时把手动 span 收敛到统一入口、统一模型调用器、统一工具执行器中。

### 3.2 Phoenix / OTLP 设置是否合理

作为开发调试合理，但生产配置还不完整。

当前 `TraceConfig.development()` 使用 `http://localhost:6006/v1/traces`。在 Android 设备上，`localhost` 指向设备本机，不是开发电脑；只有在配合 `adb reverse tcp:6006 tcp:6006` 时才成立。这个约束应该在文档和配置命名中明确，否则真机环境容易误判为 trace 失效。

生产侧当前 `TraceConfig.production()` 是全局 disabled，这是安全保守的；但如果未来要在车机内测或量产阶段保留低采样 trace，还需要增加：

- endpoint 外部配置；
- 采样率；
- 环境标签；
- sourceApp / requestId / inputType 等低敏业务属性；
- 统一脱敏；
- exporter 失败降级策略说明。

### 3.3 依赖版本设置

`libs.versions.toml` 中 OpenTelemetry 统一为 `1.48.0`，依赖包括 API、SDK、OTLP exporter；这种最小依赖组合是干净的。需要注意的是，`langchain4j-http-client` 当前写死为 `1.1.0`，而 LangChain4j 主依赖是 `1.16.3`，这不是 trace 问题本身，但会影响 OkHttp 适配层行为的一致性，建议后续单独确认版本兼容性。

## 4. 手动埋点方式评价

### 4.1 手动埋点是否合理

合理。Agent 系统的核心可观测对象不是普通 HTTP 请求，而是业务语义：

- 一次 Agent 请求；
- 第几轮 Agent loop；
- 哪个 persona；
- LLM 请求/响应/token；
- Tool call 名称、参数、结果；
- 安全拦截；
- 记忆提取/压缩；
- 场景识别；
- VLM 问答；
- 车辆状态变化。

这些语义自动 instrumentation 很难完整知道，所以手动业务埋点是必要的。

### 4.2 当前手动埋点的问题

当前手动埋点分散在 `AIAgentService` 和 `AgentLoopOrchestrator`，并依赖调用方手动把 `TraceContext` 塞进 `extraContext`。这个方式有两个问题：

1. 覆盖容易漏。新增 `IMAGE`、`CONTROL`、scene 入口后没有自然继承 trace。
2. `AgentLoopOrchestrator` 对 `extraContext` 的假设过强：只判断 `extraContext != null`，没有判断 `_trace_context` 是否存在。

更合理的模式是：在统一请求入口 `processAgentRequest(AgentRequest)` 创建 root trace，然后所有分支都使用同一个请求上下文；在模型调用统一层 `ModelCaller` 和工具统一层 `ToolExecutor/ToolRegistry` 自动创建子 span。这样业务方新增 persona 或 inputType 时，不需要记住手动埋点。

### 4.3 OkHttp interceptor 的定位

当前 interceptor 只是在“当前活跃 span”上追加 HTTP 属性，不创建独立 HTTP client span。这对 Phoenix 中观察 LLM span 很方便，但严格来说不是标准 HTTP instrumentation。

更完整的方式有两种：

- 继续将 HTTP 属性挂在 `llm.call` span 上，保持视图简洁；
- 或引入独立 `http.client` 子 span，用标准 HTTP 语义字段，比如 `http.request.method`、`url.full`、`server.address`、`http.response.status_code`。

当前字段 `http.method`、`http.url`、`http.status_code` 属于较旧/非当前稳定语义的命名，后续如要接入通用 OTel 后端，建议迁移到稳定 HTTP semantic conventions。

## 5. 关键问题清单

### P0：`AgentLoopOrchestrator` 对 trace context 的空值处理有风险

位置：`AgentLoopOrchestrator.execute()`

当前逻辑：

```java
TraceSession traceSession = extraContext != null
        ? ((TraceContext) extraContext.get(TraceContext.TRACE_CONTEXT_KEY)).session()
        : null;
```

如果 `extraContext` 非空但没有 `_trace_context`，就会空指针。当前场景链路正是这样调用：

```kotlin
sceneOrchestrator.execute("", mapOf("scene" to scene))
```

影响：

- 场景触发 Agent 可能直接失败，返回“场景服务暂时不可用”；
- 这属于 trace 改造引入的耦合风险；
- 也说明 trace 还没有做到“可选 no-op，不影响业务”。

建议优先修复为：只有 `extraContext` 中存在且类型为 `TraceContext` 时才取 session，否则 `traceSession = null`。

### P1：`IMAGE` 请求没有 trace，和实现总结文档不一致

`trace-system-summary.md` 写到 Round 3 已补齐 `sendMessageWithImage` trace，但当前代码已经改造为统一入口后，`handleImageRequest()` 没有创建 `TraceSession`，也没有给 VLM 调用传递 trace。

影响：

- 图像问答无法在 Phoenix 中看到 root span；
- VLM 延迟和失败不可定位；
- 文档与代码状态不一致。

建议：`handleImageRequest()` 创建 `agent.vision_qa` 或 `agent.image` root span，并记录 `request.id`、`session.id`、`source.app`、`image.path.exists`、`image.bytes.size` 等低敏属性。

### P1：VLM 与场景识别模型没有接入 tracing interceptor

`VlManager` 和 `SceneMatch` 都自己创建 `OpenAiChatModel`，但没有添加 `TracingOkHttpInterceptor`。即使外层有 active span，这两个模型调用也不会写入 HTTP 属性。

建议：所有 OpenAI/DashScope 模型创建都收敛到一个模型工厂，例如 `ModelFactory` 或 `AgentModelProvider`，统一注入 timeout、baseUrl、apiKey、interceptor、modelName、trace 属性。

### P1：记忆系统的隐性 LLM 调用没有 span

`MemoryCompressor.summarize()` 和 `MemoryExtractor.extract()` 会调用 LLM，但当前没有 `memory.compress` / `memory.extract` span，也没有 HTTP 属性。实际运行中，用户一次普通文本请求可能触发主 LLM + 记忆提取 LLM，后者成本和失败都被隐藏。

建议：在 `MemoryOrchestrator.onTurnComplete()` 或 `MemoryCompressor` / `MemoryExtractor` 内部增加子 span，至少记录：

- `memory.operation = extract/compress`
- `memory.input_chars`
- `memory.candidate.count`
- `memory.compressed = true/false`
- LLM token 和耗时

### P1：脱敏设计未接入，development 还关闭脱敏

`TraceRedactor` 已存在，`TraceConfig` 也有 `redactSensitive`，但当前写入 span 的位置没有使用它。`TraceConfig.development()` 还设置了 `redactSensitive(false)`。

影响：

- 用户输入、工具参数、工具输出、模型输出可能直接进入 Phoenix；
- 图像路径、手机号、位置、车辆状态、用户偏好都可能是敏感数据；
- 开发环境也可能被多人访问或落盘。

建议：即使 development，也默认开启脱敏；仅允许通过显式 debug 配置短期开启原文。脱敏应集中在 `TraceSession.setAttribute()` 或专门的 `TraceAttributeWriter`，不要散落在业务代码。

### P1：root span 业务属性过少

当前 root span 只有 `session.persona`、`user.id`、`user.input`。统一请求结构体已经有更多可用于关联的低敏字段，但没有写入：

- `request.id`
- `session.id`
- `source.app`
- `input.type`
- `scene.type`
- `response.success`
- `response.error_type`
- `response.text.length`
- `timeout.ms`

这些属性对于多应用调用、问题复现、统计不同入口质量非常关键。

### P2：模型名写死为 `"qwen"`

`AgentLoopOrchestrator` 调用 `traceSession.startLlmSpan("qwen", allMessages.size())`，丢失了真实模型名：

- chat 是 `qwen-turbo`
- scene 是 `qwen-flash`
- vision 是 `qwen-vl-max` / `qwen3-vl-plus`

建议：让 `ModelCaller` 暴露 modelName，或在 `AgentConfig` 增加 `modelName` 字段。

### P2：Tool span 粒度偏粗

当前 `tool.execute` 记录了工具名、参数、输出。对普通工具足够，但对车控场景还缺少：

- 参数校验是否通过；
- 安全策略是否 veto；
- 车辆状态变更前后快照；
- 状态机错误码；
- tool 所属 subsystem。

建议在 `ToolRegistry` / `ToolDispatcher` 层统一补充 `tool.subsystem`、`tool.success`、`tool.error_type`；对于 VehicleStateMachine，可记录状态变更摘要，不建议记录完整大 JSON。

### P2：HTTP span 错误状态不完整

`TracingOkHttpInterceptor` 在 IOException 时 `recordException()` 并写 `http.status_code=0`，但没有设置 span status 为 ERROR；HTTP 4xx/5xx 也只写状态码，没有设置 ERROR 或 `error.type`。

建议按照 OTel HTTP 语义，客户端 4xx/5xx 或网络异常时设置 span status/error type。

## 6. 是否有更合理简单的方法

有，但不是“完全自动化替代手动埋点”。

推荐分三层简化：

### 6.1 请求入口层：统一 root trace

在 `processAgentRequest(AgentRequest)` 或一个 `executeWithTrace(request, block)` 包装函数中统一创建 root span。所有 `TEXT/IMAGE/VOICE/CONTROL` 分支都在这个 root span 下执行。

这样新增 inputType 时默认有 trace，不会漏掉入口。

### 6.2 AgentLoop 层：保留业务手动 span，但集中在抽象层

LLM span 应放在统一 `ModelCaller` 或 `TracingModelCaller` 中，而不是散落在 `AgentLoopOrchestrator`。Tool span 应放在 `ToolRegistry.dispatch()` 或 `TracingToolExecutor` 中。

这样新增 persona 或替换模型时，trace 自动继承。

### 6.3 模型工厂层：统一网络和模型属性

所有 DashScope/OpenAI-compatible 模型都通过统一工厂创建：

- chat model；
- scene model；
- VLM model；
- memory summary model；
- memory extraction model。

统一注入 OkHttp interceptor、timeout、modelName、provider、baseUrl。这样不会出现某些模型有 HTTP trace、某些没有。

## 7. 推荐目标 Span 结构

建议最终形成如下结构：

```text
agent.request
├── request.route
├── camera.capture              optional
├── scene.match                 optional
│   └── gen_ai.chat             qwen3-vl-plus
├── agent.loop
│   ├── preprocess.memory
│   ├── preprocess.vehicle_status
│   ├── gen_ai.chat             qwen-turbo/qwen-flash
│   │   └── http.client         optional
│   ├── tool.execute
│   │   └── vehicle.state_update
│   ├── gen_ai.chat
│   └── memory.extract
│       └── gen_ai.chat
├── vl.qa                       optional
│   └── gen_ai.chat             qwen-vl-max
├── tts.speak                   optional
└── response.dispatch
```

其中 `http.client` 可以独立成子 span，也可以继续作为 `gen_ai.chat` 的属性；如果主要使用 Phoenix 观察 Agent 思考过程，保持属性方式更简洁。

## 8. 建议修复优先级

### 第一阶段：先保证不影响业务

1. 修复 `AgentLoopOrchestrator` 缺失 `_trace_context` 时的空指针风险。
2. 给 `handleImageRequest()` 创建 trace session。
3. 场景触发链路创建 `agent.scene` root span，或者至少传入已有 trace context。
4. `TraceRedactor` 真正接入 attribute 写入路径。

### 第二阶段：补齐核心覆盖

1. 将所有模型创建收敛到统一工厂，并统一注入 `TracingOkHttpInterceptor`。
2. VLM、SceneMatch、MemoryCompressor、MemoryExtractor 增加 span。
3. root span 增加 `requestId/sessionId/sourceApp/inputType/errorType`。
4. LLM span 记录真实模型名和 persona。

### 第三阶段：规范化与生产准备

1. 迁移 HTTP 属性到当前稳定语义字段。
2. 参考 GenAI semantic conventions，逐步使用 `gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens` 等字段。
3. 加入采样率、环境开关、endpoint 外部配置。
4. 给 Trace 系统增加最小验证用例或调试脚本，防止后续入口改造再次漏 trace。

## 9. 最终评价

当前 Trace 系统的骨架设计是对的，尤其是 root span + LLM span + Tool span 的方向符合 Agent 中枢的核心观测需求。问题不在“手动埋点本身”，而在埋点还不够集中，导致统一入口和新增链路改造后覆盖出现回退。

当前最需要调整的是 trace 的集成边界：从“调用处手动塞 TraceContext”改为“统一请求入口自动创建 root trace，统一模型调用器和工具执行器自动创建子 span”。这样既能保持业务语义，又能减少遗漏。

如果目标是 Demo 调试，当前 TEXT/VOICE 主链路已经有价值；如果目标是证明“全链路追踪系统完成”，还需要补齐 IMAGE、scene、VLM、memory、control、TTS/Camera 等链路，并修复 trace context 空值风险。

## 10. 参考资料

- 当前实现文档：`docs/act_summary/trace-system-summary.md`
- 项目入口：`app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Trace 实现：`app/src/main/java/com/hirain/aiagent/trace/`
- Agent 循环：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- OpenTelemetry Android Agent：`https://github.com/open-telemetry/opentelemetry-android`
- OpenTelemetry Java Agent：`https://opentelemetry.io/docs/zero-code/java/agent/`
- OpenTelemetry HTTP semantic conventions：`https://opentelemetry.io/docs/specs/semconv/http/http-spans/`
- OpenTelemetry GenAI semantic conventions：`https://github.com/open-telemetry/semantic-conventions-genai`
