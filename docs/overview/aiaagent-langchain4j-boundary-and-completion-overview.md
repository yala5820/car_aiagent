# AIAgent 自研边界与 LangChain4j 复用建议

日期：2026-07-09

## 1. 总体结论

AIAgent 不适合整体迁移为 LangChain4j `AiServices` 托管式 Agent。当前项目的核心价值不是“调用大模型”，而是围绕 Android 车机后台 Service 构建一个可控的车载 Agent Runtime：AIDL 请求边界、会话身份、上下文策略、车控安全、工具组裁剪、取消请求、Trace、虚拟车辆状态机和测试 App 协议对接都属于车机场景强业务逻辑。

推荐路线是：

> AIAgent 保持自研车载 Agent Runtime，负责车机业务语义、上下文策略、会话身份、安全控制、工具分组、Trace 与系统服务边界；LangChain4j 作为底层 LLM 基础设施，负责模型调用、消息类型、ChatMemory 接口、Tool schema、动态工具接入和可选的结构化输出、RAG 能力。

这个判断不是否定 LangChain4j。相反，LangChain4j 应该被更稳定地用在通用 LLM 基础设施层，避免项目继续重复实现模型协议、消息协议、工具 schema、记忆存储接口等通用能力。真正需要自研的是“车载 Agent 应该如何理解、选择、执行和兜底”。

## 2. 完成度口径

本文使用以下完成度口径：

| 完成度 | 含义 |
| --- | --- |
| 已完成 | 主链路已有源码接入，且已有对应测试或相对明确的运行闭环。 |
| 基本完成 | 主体能力已落地，但仍有局部边界、测试或工程收敛事项。 |
| 部分完成 | 已有核心类或接口，但还没有完整接入真实主路径，或只覆盖第一版范围。 |
| 规划待落地 | 规划文档明确提出，但当前源码中尚未完整实现。 |
| 持续演进 | 不应以“一次完成”为目标，需要按业务场景持续扩展。 |

## 3. LangChain4j 能力边界参考

LangChain4j 官方文档对高低层能力边界有一个清晰提示：直接使用 `ChatModel`、`ChatMessage`、`ChatMemory` 等低层组件自由度高，但样板代码会增加；`AI Services` 则用于隐藏常见 LLM 交互复杂度，并支持 chat memory、tools、RAG 等能力。参考：[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)。

这对 AIAgent 的含义是：

- 可以使用 `ChatModel`、`ChatRequest`、`ChatMessage` 保留主循环控制权。
- 可以使用 `ChatMemory` / `ChatMemoryStore` 作为短期记忆标准接口。
- 可以使用 `@Tool`、`ToolSpecification`、`ToolProvider` 降低工具 schema 和动态工具暴露成本。
- 不建议让 `AiServices` 接管主 AgentLoop，因为当前主链路需要显式控制车控安全、Trace、取消、终态抢占和多轮 tool loop。

LangChain4j Chat Memory 支持通过 memory id 和 `ChatMemoryStore` 做持久化，且消息淘汰会同步到 store。参考：[Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)。

LangChain4j Tools 支持 `@Tool` 方法发现、参数 schema、动态工具提供和 tool search。参考：[Tools](https://docs.langchain4j.dev/tutorials/tools/)。

## 4. 建议完全自研的模块

这些模块体现项目的车机业务独创性，不建议交给 LangChain4j 高层抽象托管。

| 模块 | 建议 | 当前完成度 | 当前源码现状 | 后续建议 |
| --- | --- | --- | --- | --- |
| `AIAgentService` / AIDL 服务边界 | 完全自研 | 基本完成 | `IAIAgentAidlInterface.aidl` 已包含 `processAgentRequest`、会话 CRUD、取消、listener；`AIAgentService.kt` 已委托 `AgentRuntime`、`ConversationManager`、`ActiveRequestRegistry`。 | 保持同版本 SDK/Service 末尾追加字段策略；不要把 Binder 生命周期、前台 Service、listener 分发交给 LLM 框架。 |
| `AgentRuntime` | 完全自研 | 基本完成 | 已集中处理 `RequestSession`、`IntentRouter`、`ToolGroupSelector`、`ContextOrchestrator.build(session)`、runtime 取消检查和 `RuntimeResult` 映射。 | 继续作为 Service 与 AgentLoop 的唯一协调层；后续 LangChain4j 能力只应从这里下沉到模型/记忆/tool 基础设施。 |
| `AgentLoopOrchestrator` 业务编排 | 保持自研主循环 | 基本完成，但仍需会话记忆收敛 | 已自研 ModelCaller、ToolSafetyEngine、ToolExecutor、PostProcessor、Terminator、ResultCollector 等执行管线；用 LangChain4j `ChatRequest` 发模型请求。 | 主循环不要整体迁移到 `AiServices`；但要把固定 `chatMemory` 继续收敛为按 `userId/sessionId/personaId` 选择的 LangChain4j `ChatMemory`。 |
| context 策略层 | 完全自研 | 基本完成第一版 | 已有 `ContextFrame`、`ContextProvider`、`ContextOrchestrator`、9 个 provider、`ContextBudgetManager`、`ContextTraceRecorder`，并通过 `ContextExtraPreProcessor` 首轮注入模型输入。 | 继续自研上下文优先级、裁剪、fallback、车况/工具组/长期记忆策略；底层消息仍使用 LangChain4j `ChatMessage`。 |
| context 完整语义层 | 完全自研 | 部分完成 | 当前一期采用 `HYBRID_EXTRA_CONTEXT`，`FULL_CONTEXT` 在源码中仍降级处理；Memory provider 一期不读取完整短期 `ChatMemory`，tool provider 一期只渲染工具组说明和 toolName。 | 后续分阶段补齐真实 memory summary、完整 selected tool spec、token budget 和压缩策略，不要一次性扩大到全量上下文系统。 |
| 车控安全层 | 完全自研 | Demo 完成 | 共享 `ToolSafetyEngine` 已在所有 AgentLoop 工具执行前审核；当前显式覆盖静止解锁和静止切换底盘模式，其他低风险 Tool 默认放行。 | 车控安全必须保持确定性，不能依赖 LLM 自觉；LangChain4j 只能作为工具调用输入来源。 |
| `VehicleStateMachine` | 完全自研 | 基本完成 | 已作为 Demo 阶段车控 tool 的状态托管中心，管理参数校验与状态收敛。 | 继续作为车控仿真/验收基础；后续接真实 SOA 时也应保留状态校验边界。 |
| `IntentRouter` | 建议自研 | 基本完成 | 已有 `KeywordIntentRouter`、`IntentTag`、`IntentConfidence` 和测试。 | 第一版继续用轻量规则；后续可考虑用 LangChain4j 结构化输出做“辅助分类器”，但最终路由策略仍由自研层裁决。 |
| `ToolGroupSelector` / `ToolGroupRegistry` | 建议自研 | 基本完成 | 已有 13 个工具组、默认注册表、基于意图和车载关键词的选择器、单元测试。 | 保留自研工具组策略；后续可把选择结果映射到 LangChain4j `ToolProvider` 或 tool search，而不是让 LLM 直接看全量工具。 |
| 业务 Trace | 完全自研 | 基本完成 | 已有 `TraceManager`、`TraceSession`、`AgentTraceRecorder`、context trace、HTTP interceptor。 | 继续记录业务语义 span，例如 request、context build、prompt assembly、tool execute、memory extract/compress；LangChain4j observability 只能补低层维度。 |
| 取消与终态抢占 | 完全自研 | 基本完成 | `ActiveRequestRegistry`、`ActiveRequest.tryComplete`、`cancelAgentRequest`、late result suppression 已接入 Service。 | 不要承诺硬中断 LLM HTTP；验收目标应是取消响应抢占和 late result 不再分发。 |
| 测试 App 会话 UI / 本地状态 | 完全自研 | 由测试 App 决定，服务侧接口已具备 | AIAgent 服务侧已有会话 CRUD AIDL；测试 App 的 Drawer、Room、ChatAdapter、消息列表切换属于客户端状态。 | 测试 App 只负责 UI 和把正确 `userId/sessionId/personaId/clientMessageId` 传入 AIAgent；不要试图用 LangChain4j 管 Android UI 会话。 |

## 5. 建议基于 LangChain4j 开发的模块

这些模块属于通用 LLM 基础设施。建议继续复用 LangChain4j，避免重复造基础框架。

| 模块 | 建议 | 当前完成度 | 当前源码现状 | 后续建议 |
| --- | --- | --- | --- | --- |
| LLM 模型接入 | 基于 LangChain4j | 已完成 | `AgentConfigFactory` 使用 `OpenAiChatModel.builder()` 接 qwen 模型；`Lc4jModelCaller` 只是 `ChatModel.chat(request)` 薄封装。 | 保持 LangChain4j OpenAI 兼容模型接入；不要自写 DashScope HTTP 协议和响应解析。 |
| `ChatRequest` / `ChatMessage` / `ChatResponse` | 基于 LangChain4j | 已完成 | `AgentLoopOrchestrator` 构造 `ChatRequest.builder().messages(...).toolSpecifications(...)`；PreProcessor 返回 LangChain4j `ChatMessage`。 | 继续使用 LangChain4j 消息模型，避免自定义一套中间消息协议。 |
| 短期记忆底座 | 基于 LangChain4j `ChatMemory` / `ChatMemoryStore` | 部分完成 | 主循环当前仍在构造期固定创建 `MessageWindowChatMemory + PersistentChatMemorySqlite(config.chatMemoryStoreId())`；同时已有 `SessionMemoryStore implements ChatMemoryStore` 和 `SessionMemoryIds`。 | 优先完成 `SessionChatMemoryProvider` 或等价机制，使每次执行按 `userId/sessionId/personaId` 解析 memory id，并以 `SessionMemoryStore` 作为标准 `ChatMemoryStore`。 |
| 会话 metadata 存储 | 自研业务表 + LangChain4j store 接口 | 部分完成 | `SessionMemoryStore` 既承担 `ChatMemoryStore`，又维护 sessions 表、title、persona、sourceApp、active、messageCount 等 metadata。 | 这部分设计方向正确；但需要让主 AgentLoop 真正使用该 store，而不是只在会话管理门面里维护 metadata。 |
| Tool schema 生成 | 基于 LangChain4j | 基本完成 | `ToolRegistry` 使用 `ToolSpecifications.toolSpecificationsFrom(mgr.getClass())` 生成 schema，工具方法使用 `@Tool`。 | 继续沿用 `@Tool` / `ToolSpecification`；不要手写全量工具 JSON schema。 |
| Tool 执行分发 | 当前自研，建议逐步贴近 LangChain4j | 部分完成 | `ToolDispatcher` 自研反射执行，按 toolName 分发；但参数解析仍有实现与注释不一致风险，历史评估指出 positional `arg0/arg1` 仍是关键路径。 | 短期保留自研分发以支撑车控安全；中期评估是否复用 LangChain4j tool executor / tool provider 模式，至少补齐参数解析测试。 |
| 动态工具暴露 | 基于 LangChain4j 思路，自研策略输出 | 部分完成 | 已有 `ToolGroupSelector` 做业务选择；`AgentLoopOrchestrator` 仍使用 `effectiveToolSpecs` 一次性放入 `ChatRequest`。 | 让自研 `ToolGroupSelector` 决定候选工具，再向 LangChain4j `ToolProvider` 或低层 `toolSpecifications` 输出，不要让 LLM 直接面对全量车控工具。 |
| 结构化输出 | 建议基于 LangChain4j | 规划待落地 | 当前意图路由主要是关键词/正则；记忆提取、摘要有自研 prompt 和 parser。 | 对低风险分类任务可试点 `AiServices` 结构化返回，例如意图分类、摘要质量评估、记忆候选打分；不要替换主 Runtime。 |
| RAG / embedding / 文档检索 | 建议基于 LangChain4j | 规划待落地 | 当前项目没有完整 RAG 子系统。 | 如果后续接车机手册、功能说明书、故障码知识库，应优先使用 LangChain4j RAG/embedding/vector store 能力。 |
| 非核心小 Agent | 可局部试用 `AiServices` | 规划待落地 | 主链路未使用 `AiServices`，当前是自研 `AgentLoopOrchestrator` + `ChatModel`。 | 可选一个旁路小组件做 spike，例如“记忆提取器”“场景摘要器”“意图分类器”；不要把主聊天/车控 Agent 一步迁过去。 |

## 6. 当前完成度细化评估

### 6.1 主 Agent Runtime

当前完成度：基本完成。

已完成内容：

- `AIAgentService` 已作为 AIDL Binder 入口，负责请求进入、listener 注册、超时、取消和结果分发。
- `AgentRuntime.startSession()` 已将 request 规范化为 `RequestSession`，并完成意图路由与工具组选择。
- `AgentRuntime.execute()` 已在进入 AgentLoop 前构建 `ContextFrame`，并在 context build 后增加取消检查。
- `RuntimeResponseMapper` 将 runtime 结果映射回 `AgentResponse`。

仍需注意：

- Runtime 已经变成核心协调层，后续不要再从 Service 直接绕过 Runtime 调用 AgentLoop。
- `VOICE` 等非 TEXT 路径如果尚未完全迁移到 Runtime，应继续按阶段控制范围，不要为了统一架构一次性扩大。

建议归属：

- Runtime 本身完全自研。
- Runtime 调用的底层模型、消息、记忆、工具 schema 继续基于 LangChain4j。

### 6.2 AgentLoopOrchestrator

当前完成度：基本完成，但短期记忆隔离仍需要收敛。

已完成内容：

- 主循环已明确分成模型调用、ToolSafetyEngine、ToolExecutor、PostProcessor、Terminator、ResultCollector 等阶段。
- 模型调用使用 LangChain4j `ChatRequest` / `ChatResponse`。
- Tool call 结果通过 LangChain4j `ToolExecutionResultMessage` 回填。
- `ContextExtraPreProcessor` 已能把 context 模块渲染结果放入 `ChatRequest.messages()`。

主要风险：

- 当前 `AgentLoopOrchestrator` 仍有构造期固定 `chatMemory` 的路径。
- `createChatPersona()` 仍存在固定 `.chatMemoryStoreId("ChatMemory")` 的普通 chat persona；新的 `memoryId` persona factory 已出现，但需要确保真实请求都走到按 `userId/sessionId/personaId` 选择的路径。
- 如果只修改会话 metadata，不改主循环的 active `ChatMemory` 来源，外部看起来会话切换了，但模型上下文仍可能没有真正隔离。

建议归属：

- 主循环控制权自研。
- `ChatMemory`、`ChatRequest`、`ToolSpecification` 使用 LangChain4j。

### 6.3 Context 模块

当前完成度：第一版基本完成。

已完成内容：

- 已有 `ContextFrame`、`ContextSection`、`ContextMode`、`ContextProviderResult` 等数据模型。
- 已有 `ContextBudgetManager` 做 section 和 total char limit 裁剪。
- `ContextOrchestrator.defaultForText()` 已注册 9 个 provider：runtime、persona、user input、intent、tool group、memory、vehicle state、time、prompt。
- `ContextTraceRecorder` 已记录 context 构建诊断。
- `ContextExtraPreProcessor` 已解决“context 只在 map 中、不进入模型输入”的关键问题。
- 单元测试目录已包含 context、provider、ContextExtraPreProcessor、AgentLoop context injection 等测试类。

一期边界：

- `FULL_CONTEXT` 当前仍按 HYBRID 行为降级。
- Memory provider 一期偏 metadata / owner 信息，不读取完整短期 `ChatMemory` 历史。
- Tool group provider 一期只渲染 selected group 描述与 selected toolName，不渲染完整 LangChain4j `ToolSpecification.description` 和参数 schema。
- Context 模块不应该重复注入 system prompt、长期记忆、车辆状态、时间等已由其他 PreProcessor 注入的内容。

建议归属：

- 上下文选择、排序、裁剪、fallback、业务语义完全自研。
- 最终进入模型的载体使用 LangChain4j `ChatMessage`。
- 后续如果改用 `AiServices` 的 `chatRequestTransformer`，也只能作为局部实验，不应替代当前 Runtime + PreProcessor 路径。

### 6.4 会话管理与短期记忆隔离

当前完成度：会话 metadata 基本完成，真实短期 ChatMemory 隔离部分完成。

已完成内容：

- AIDL 已提供 `createConversation`、`listConversations`、`deleteConversation`、`switchConversation`、`getActiveConversation`。
- `ConversationManager` 已作为面向 AIDL 的会话门面。
- `SessionManager` 已按 `userId` 维护 active session。
- `SessionMemoryStore` 已实现 LangChain4j `ChatMemoryStore`，并维护 sessions metadata。
- `SessionMemoryIds` 已提供 `userId_sessionId_personaId` 格式。
- 计划文档已经明确 Phase 3A 要引入 `SessionChatMemoryProvider`，使 AgentLoop 每次执行按请求选择 `ChatMemory`。

仍未完全闭环：

- 源码目录中当前没有独立的 `SessionChatMemoryProvider.java` 文件。
- `SessionMemoryStore.buildMemoryId(userId, sessionId)` 与 `SessionMemoryIds.build(userId, sessionId, personaId)` 两套 memory id 思路需要统一。
- `MemoryOrchestrator.onTurnComplete()` 压缩后写回 `SessionMemoryStore.updateMessages(...)`，但需要确认该 store 就是当前 active `ChatMemory` 的实际 store，否则压缩只写 metadata/store，不一定改变模型下轮读取的短期历史。

建议归属：

- 会话生命周期、active session、AIDL 协议、测试 App 会话 UI 完全自研。
- 每个会话的短期消息存储基于 LangChain4j `ChatMemory` / `ChatMemoryStore`。
- memory id 标准建议统一为 `userId + sessionId + personaId`，避免同会话不同 persona 串上下文。

### 6.5 Tool 系统

当前完成度：工具注册和调用链基本完成，动态工具治理部分完成。

已完成内容：

- 车控、天气、视觉等工具以 `@Tool` 方法声明。
- `ToolRegistry` 使用 LangChain4j `ToolSpecifications.toolSpecificationsFrom(...)` 自动生成 tool schema。
- `ToolDispatcher` 自研反射分发 tool call。
- `AgentConfigFactory` 将 `toolRegistry::dispatch` 注入不同 persona。
- Scene persona 已支持 `filterSceneTools(...)` 做子集裁剪。
- `ToolGroupRegistry` 和 `DefaultToolGroupSelector` 已补上工具组级策略。

主要风险：

- `ToolDispatcher` 的参数解析仍是高风险点，尤其是命名参数与 `arg0/arg1` 兼容路径。
- `SCENE_TOOL_MAP` / 工具组配置需要持续跟真实 toolName 对齐。
- 目前 selected tools 主要作为 schema 子集进入请求，尚未完全转成 LangChain4j `ToolProvider` 风格的动态按需工具提供。

建议归属：

- 工具组选择、车载场景过滤、安全裁决自研。
- tool schema、`@Tool` 注解、参数描述、未来动态工具 provider 尽量基于 LangChain4j。

### 6.6 Prompt / Persona

当前完成度：基础已完成，治理层持续演进。

已完成内容：

- Prompt 模板已外部化到 `assets/prompts/`。
- `PromptManager`、`PromptConstants`、`PromptSelector` 已形成基础能力。
- `AgentConfigFactory` 已支持 `chat/friendly/concise` 等 TEXT persona factory。

仍需注意：

- Prompt 模板不是完整 context 系统。Context 系统还包括收集、排序、裁剪、压缩、预算和最终模型可见输入。
- Persona 切换必须和短期 `ChatMemory` 隔离绑定，否则不同性格会共享短期历史。

建议归属：

- Prompt 文件管理和 persona 业务策略自研。
- 如果后续某些小 Agent 使用 `AiServices`，可以借鉴 `@SystemMessage` 或 `systemMessageProvider`，但主链路仍应通过现有 PromptManager + AgentConfigFactory 管理。

### 6.7 Memory 提取、压缩和长期记忆

当前完成度：部分完成。

已完成内容：

- `LongTermMemoryStore`、`MemoryExtractor`、`MemoryCompressor`、`MemoryOrchestrator` 已具备基础结构。
- `MemoryExtractor` 和 `MemoryCompressor` 已接入 trace-aware overload。
- `MemoryPreProcessor` 已能在首轮注入长期记忆。

主要风险：

- 压缩写回和当前 active `ChatMemory` 之间的闭环仍需要继续验证。
- Memory provider 当前不应被误认为已经完成完整短期上下文治理。
- 记忆提取和压缩如果使用纯文本 parser，长期会比结构化输出更脆弱。

建议归属：

- 记忆策略、什么信息值得长期记忆、何时压缩、如何保护隐私完全自研。
- 摘要模型调用、结构化候选输出、ChatMemory 存储接口基于 LangChain4j。

### 6.8 Trace / Observability

当前完成度：基本完成。

已完成内容：

- 已有业务 trace facade、request root span、model span、tool span、memory extract/compress span、context trace。
- `TracingOkHttpInterceptor` 记录底层 HTTP 属性。
- 多个 trace 单元测试已存在。

建议归属：

- 业务 span 自研。
- 如果引入 LangChain4j observability，只作为底层补充，不替代当前车机业务 trace 语义。

### 6.9 测试 App 会话管理

当前完成度：服务侧协议已具备，客户端完成度需以 AIAgentTestApp 当前源码为准。

AIAgent 服务侧已经提供必要接口：

- 创建会话：`createConversation`
- 会话列表：`listConversations`
- 删除会话：`deleteConversation`
- 切换会话：`switchConversation`
- 查询活跃会话：`getActiveConversation`
- 请求取消：`cancelAgentRequest`
- 主请求仍通过 `processAgentRequest(AgentRequest)`

测试 App 自身仍必须自研：

- 会话列表 UI
- 本地消息缓存
- 当前会话切换
- 消息清空或替换
- 用户 / persona 选择
- AIDL 连接状态和 listener 状态
- `clientMessageId` 与服务端响应对账

LangChain4j 对 Android 客户端 UI 状态没有帮助。测试 App 的职责是把正确身份字段传给 AIAgent，并正确展示服务端返回。

## 7. 建议的后续优先级

### 优先级 P0：完成真实短期 ChatMemory 隔离闭环

目标：

- 每次 TEXT 请求都按 `userId/sessionId/personaId` 选择 `ChatMemory`。
- 同用户不同 session 不共享短期历史。
- 同 session 不同 persona 不共享短期历史。
- 删除 session 后，对应短期历史不可恢复。

建议做法：

- 落地 `SessionChatMemoryProvider` 或等价 provider。
- 统一 `SessionMemoryIds` 和 `SessionMemoryStore.buildMemoryId`。
- 改造 `AgentLoopOrchestrator`，不要在构造期固定唯一 persistent `chatMemory`。
- 增加 AgentLoop 级测试，用 fake `ModelCaller` 捕获 `ChatRequest.messages()` 验证隔离。

### 优先级 P1：收敛 Tool schema 与动态工具暴露

目标：

- 保留 `ToolGroupSelector` 的车载业务策略。
- 继续使用 LangChain4j `ToolSpecification`。
- 避免 LLM 看到不相关或危险工具。

建议做法：

- 补齐 `ToolDispatcher` 参数解析测试。
- 建立 selected toolName 到真实 `ToolSpecification` 的只读查询能力。
- 评估将 `ToolGroupSelector` 输出适配为 `ToolProvider` 风格，而不是全量工具表。

### 优先级 P2：增强 context 模块，但不要重复注入

目标：

- 保证 context 进入模型输入。
- 保证 context 不重复注入已有 PreProcessor 内容。
- 保证预算裁剪可解释、可测试、可 trace。

建议做法：

- 保持 `HYBRID_EXTRA_CONTEXT` 为默认模式。
- 后续再打开 `FULL_CONTEXT`，并先明确哪些 section 替代旧 PreProcessor，哪些只做 debug metadata。
- 引入 selected tool spec 描述时，先走只读数据结构，不改变 tool 执行路径。

### 优先级 P3：选择一个小 Agent 试点 `AiServices`

目标：

- 验证 LangChain4j 高层接口是否能减少非核心样板代码。
- 不影响主 AgentLoop、车控、取消、Trace。

适合试点：

- 意图分类器
- 记忆候选提取器
- 摘要质量评估器
- 用户偏好结构化抽取

不适合试点：

- 主聊天 AgentLoop
- 车控 tool loop
- AIDL 主请求路径
- 取消和终态抢占

## 8. 最终边界图

```text
完全自研层
  AIAgentService / AIDL
  AgentRuntime
  ContextOrchestrator / ContextPolicy
  IntentRouter / ToolGroupSelector
  AgentLoopOrchestrator business loop
  ToolSafetyEngine / VehicleStateMachine
  ConversationManager / Session lifecycle
  Trace business spans
  TestApp UI conversation state

LangChain4j 基础设施层
  ChatModel / OpenAiChatModel
  ChatRequest / ChatMessage / ChatResponse
  ChatMemory / ChatMemoryStore
  @Tool / ToolSpecification
  ToolProvider / tool search, if adopted
  Structured outputs, if adopted
  RAG / embeddings, if adopted
```

## 9. 简明决策表

| 问题 | 决策 |
| --- | --- |
| 主 Agent 是否迁移到 `AiServices`？ | 不建议。主链路需要车机业务控制权。 |
| context 模块是否放弃自研？ | 不建议。context 策略是项目独创性。 |
| 会话管理是否交给 LangChain4j？ | 不建议。会话生命周期和 UI 状态自研；短期消息底座用 LangChain4j `ChatMemory`。 |
| Tool 系统是否继续完全自研？ | 不建议完全自研。工具组、安全、执行策略自研；schema 和动态暴露贴近 LangChain4j。 |
| Memory 是否完全自研？ | 不建议。记忆策略自研；`ChatMemoryStore`、结构化输出、模型调用基于 LangChain4j。 |
| 测试 App 是否能用 LangChain4j 简化？ | 基本不能。测试 App 是 Android 客户端 UI 和 AIDL 状态管理。 |

## 10. 推荐项目定位表述

建议在后续文档中使用以下表述：

> AIAgent 是一个面向 Android 车机系统的自研车载 Agent Runtime。项目使用 LangChain4j 作为 LLM 基础设施，复用其 OpenAI 兼容模型接入、消息模型、ChatMemory 接口和 Tool Calling schema；但请求编排、上下文策略、会话身份、安全控制、工具组选择、Trace、取消请求和车机状态治理由 AIAgent 自研实现。

这个定位能同时保留项目独创性和工程可维护性：核心业务判断在自己手里，通用 LLM 基础设施交给成熟框架。
