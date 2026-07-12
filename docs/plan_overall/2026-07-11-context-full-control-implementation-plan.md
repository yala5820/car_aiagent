# AIAgent TEXT Context Full Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 TEXT 主 AgentLoop 的全部模型输入装配权迁移到 `ContextOrchestrator + ContextMessageAssembler`，由 Context 在每次模型调用前统一生成最终 `ChatMessage` 与 `ToolSpecification`，并统一处理可见性、顺序、预算、裁剪、Memory 压缩触发和 Context Trace。

**Architecture:** 保留自研 `AgentRuntime + AgentLoopOrchestrator`，继续使用 LangChain4j 的 `ChatModel`、`ChatRequest`、`ChatMessage`、`ToolSpecification` 和 `ChatMemory` 等底层原语。Context 是模型输入控制平面；Prompt、Memory、Tool、Vehicle 等模块继续拥有数据、算法、存储和执行；AgentLoop 只负责循环、模型调用、工具执行、状态推进与 ChatMemory 写入。

**Tech Stack:** Java/Kotlin、Android、LangChain4j 1.16.3、JUnit4、OpenTelemetry、SQLite ChatMemoryStore、现有 ToolRegistry/ToolGroup/Memory/Prompt/Trace 模块。

**Design Source:** `docs/superpowers/specs/2026-07-11-context-full-control-design.md`

---

## 1. 工作边界与完成标准

### 1.1 本轮必须完成

- TEXT 主 AgentLoop 每次调用模型前，只能从 Context 获取最终消息和工具规格。
- `ContextMessageAssembler` 成为唯一消息装配器；`AgentLoopOrchestrator` 不再拼接 System Prompt、临时消息和 ChatMemory。
- Context Provider 从 Prompt、Memory、Vehicle、Time、ToolRegistry 等模块读取真实数据，不再只输出 owner 或观测文本。
- Context 按请求级和迭代级分别准备数据；工具执行后的车辆状态能够在下一次迭代重新读取。
- Context 输出强类型 `ContextAssemblyResult`，至少包含最终消息、工具规格、预算报告和诊断信息。
- Context 决定是否触发短期记忆压缩及目标预算；Memory 模块执行压缩并一致性写回 live ChatMemory 与 SQLite。
- 完整预算覆盖 System、Context Data、Session ChatMemory、当前 UserMessage、Tool Schema、输出预留和安全余量。
- 建立 `context.prepare`、`context.assemble` 和真实 `agent.loop` Trace 层级，修复当前 child span 全部挂在 root 下的问题。
- 迁移期间先执行影子装配；完成差异验证后再切换真实模型输入。
- 最终删除 `ContextMode`、HYBRID/OBSERVE 分支、旧 TEXT 消息型 PreProcessor 和 Map 兼容路径，不保留单值 `FULL_CONTEXT` 模式开关。

### 1.2 本轮明确不做

- 不改造 scene、vision/VL 的独立模型输入链路。
- 不接管 `MemoryCompressor`、`MemoryExtractor` 内部辅助 LLM 调用。
- 不迁移到 LangChain4j AiServices，不替换自研 AgentLoop。
- 不重写 ToolRegistry、ToolDispatcher、PromptManager、MemoryCompressor、VehicleStateMachine 的底层实现。
- 不新增完整聊天历史查询系统；ChatMemory 仍是可压缩、可淘汰的模型记忆。
- 不引入网络 Token 服务，也不使用与 Qwen 不匹配的 tokenizer。
- Demo 阶段不新增复杂工具权限模型或工具读写分类系统。
- 不修改 AIDL 协议、会话 CRUD、用户/人格选择协议和非 TEXT 请求接口。
- 不新增第三方依赖，不修改 Gradle、AGP、Version Catalog 和本地密钥配置。

### 1.3 已确认的 Demo 决策

- `ToolGroupSelector` 明确命中时，只向模型提供选中 ToolGroup 对应的工具。
- `allToolsFallback=true` 时，为弥补规则选择器无法识别模糊指令的问题，Context 提供 ToolRegistry 当前全部已启用工具。
- 全量 fallback 是能力兜底，不是安全授权。Trace 必须记录 fallback、工具数量和最高风险等级。
- Demo 阶段暂不增加复杂工具权限模型；后续生产化必须在 ToolExecutor 前增加不可绕过、失败关闭的统一安全执行门。
- 工具规格解析失败不能静默扩大为全量工具；只有上游明确给出 `allToolsFallback=true` 才能走全量兜底。
- 全量工具在预算管理中作为一个完整候选集合处理，不允许随机截断 Tool Schema；若完整集合超出预算，应明确返回预算失败并记录原因。

### 1.4 最终完成定义

- 代码搜索证明 TEXT 主链路只有 Context 创建最终模型消息列表和工具规格列表。
- TEXT `AgentLoopOrchestrator` 不再调用 PromptManager 拼 SystemMessage，也不再读取 `transientMessages` 或固定 `effectiveToolSpecs`。
- 每次 Tool Calling 迭代都调用 Context，第二轮保留合法的 Ai Tool Request 与 Tool Result 原子关系。
- 用户切换只影响用户级长期记忆；session 切换隔离短期 ChatMemory；persona 选择仍进入 System Prompt 策略。
- 所有阶段测试、完整单元测试、`assembleDebug` 和 `lintDebug` 通过。
- 旧模式、旧兼容键、影子比较代码和废弃测试已删除。

---

## 2. 目标调用链与文件策略

### 2.1 最终 TEXT 调用链

```text
AIAgentService
  -> AgentRuntime 创建 RequestSession
  -> ContextOrchestrator.prepare(requestSession)
       -> 请求级 Provider 生成静态 Contribution
       -> ContextFrame 保存请求级单一事实来源
  -> AgentLoopOrchestrator.execute(requestSession, contextFrame)
       -> 每次迭代调用 ContextAssemblyGateway.assemble(...)
            -> 读取迭代级 Provider
            -> ContextPolicy 计算可见性/优先级/必需性
            -> ContextMessageAssembler 生成候选消息和工具规格
            -> ContextBudgetManager 估算与裁剪
            -> 必要时调用 MemoryOrchestrator.planSessionCompaction(...)
            -> Context 独占阶段调用 executeCompactionPlan(...)
            -> 重新读取 Memory 并再次装配
            -> 返回 ContextAssemblyResult
       -> AgentLoop 用 result.messages/toolSpecifications 构造 ChatRequest
       -> 模型调用、工具执行、ChatMemory 写入、下一次迭代
```

### 2.2 核心新增文件

- `app/src/main/java/com/hirain/aiagent/context/ContextContribution.java`
  - Provider 输出的基础契约；只包含稳定元数据，不使用 `Object payload`。
- `context/TextContextContribution.java`
  - 表达 System Prompt、Context Data 等文本贡献及目标消息区域。
- `context/MessageContextContribution.java`
  - 表达当前用户消息和 Session ChatMemory 消息集合。
- `context/ToolContextContribution.java`
  - 表达真实 LangChain4j `ToolSpecification` 集合。
- `context/ContextVisibility.java`
  - 至少区分模型可见、Trace-only、禁止进入模型。
- `context/ContextTrustLevel.java`
  - 区分受信系统内容、内部数据和不可信外部输入。
- `context/ContextLifecycle.java`
  - 区分 REQUEST_STATIC 与 ITERATION_DYNAMIC。
- `context/ContextPriority.java`
  - 定义 CRITICAL、HIGH、NORMAL、OPTIONAL、TRACE_ONLY。
- `context/ContextPolicy.java`、`context/DefaultContextPolicy.java`
  - 根据请求意图、Provider 来源和工具选择结果确定可见性、优先级及 required。
- `context/ContextPreparer.java`
  - 请求级准备入口。
- `context/ContextAssemblyGateway.java`
  - AgentLoop 每轮调用的稳定装配接口，便于测试替换。
- `context/ContextPrepareResult.java`
  - 请求准备结果，包含 ContextFrame 与诊断信息。
- `context/ContextCancelChecker.java`
  - Context 使用的取消窄接口，由 Runtime 现有取消来源适配。
- `context/ContextAssemblyRequest.java`
  - 包含 ContextFrame、迭代号、当前 ChatMemory 快照、工具执行状态和取消检查入口。
- `context/ContextAssemblyResult.java`
  - 最终消息、工具规格、预算报告、诊断和失败语义。
- `context/ContextAssemblyDebugInfo.java`
  - 保存不含敏感正文的装配诊断、Provider outcome 和影子比较摘要。
- `context/ContextProviderOutcome.java`
  - Provider 的 SUCCESS、FALLBACK、FAILED、CANCELLED 结果及稳定错误码。
- `context/ContextErrorCode.java`
  - Context prepare/assemble 的领域错误码，供 AgentLoop/Runtime 稳定映射。
- `context/ContextLoopStateView.java`
  - 从 AgentLoopContext 提取的只读迭代视图，仅含上一轮工具和安全状态。
- `context/ContextMessageAssembler.java`
  - 纯装配组件，不访问数据库、时间、车辆、Trace 或模型。
- `context/ContextMessageSequenceValidator.java`
  - 校验唯一 SystemMessage、消息顺序和 Tool Calling 原子关系。
- `context/ContextBudgetPolicy.java`
  - 保存模型窗口、输出预留和安全余量；数值从受控生产装配配置注入。
- `context/ContextTokenEstimator.java`、`context/HeuristicContextTokenEstimator.java`
  - 低成本保守估算及静态内容缓存。
- `context/ContextBudgetReport.java`
  - 输出消息、工具、裁剪、压缩和最终预算状态。
- `context/ModelContextWindowProfiles.java`
  - 集中提供 qwen-turbo Demo 的受控窗口配置，不读取网络或本地密钥文件。
- `memory/MemoryCompactionResult.java`
  - Memory 压缩是否执行、是否成功、压缩前后估算及错误信息。
- `memory/MemoryCompactionPlan.java`
  - Context 根据目标预算请求、Memory 生成的不可变压缩计划；生成计划不调用模型、不写 Store。
- `memory/LongTermMemorySnapshot.java`
  - 向 Context 提供稳定、结构化的长期记忆条目。
- `memory/MemoryPersistenceException.java`
  - SessionMemoryStore 完整替换失败时向上抛出的运行时异常，禁止静默吞错。

### 2.3 核心修改文件

- `context/ContextOrchestrator.java`
- `context/ContextFrame.java`
- `context/ContextFrameBuilder.java`
- `context/ContextProvider.java`
- `context/ContextProviderResult.java`
- `context/ContextBudgetManager.java`
- `context/ContextTraceRecorder.java`
- `context/provider/PromptContextProvider.java`
- `context/provider/UserInputContextProvider.java`
- `context/provider/MemoryContextProvider.java`
- `context/provider/VehicleStateContextProvider.java`
- `context/provider/TimeContextProvider.java`
- `context/provider/ToolGroupContextProvider.java`
- `memory/MemoryOrchestrator.java`
- `memory/MemoryCompressor.java`
- `memory/SessionChatMemoryProvider.java`
- `ai/langchain4j/tool/ToolRegistry.java`
- `runtime/AgentExecutor.java`
- `runtime/AgentRuntime.java`
- `core/AgentLoopOrchestrator.java`
- `core/factory/AgentConfigFactory.java`
- `trace/TraceSession.java`
- `trace/TraceSpanNames.java`
- `trace/AgentTraceRecorder.java`
- `AIAgentService.kt`

### 2.4 最终删除候选

下列文件或接口只能在引用搜索为零、非 TEXT 链路不依赖后删除：

- `context/ContextMode.java`
- `core/preprocessor/ContextExtraPreProcessor.java`
- `context/ContextSection.java`
- `context/ContextSectionType.java`
- 旧 `ContextBuildResult.java`、`ContextDebugInfo.java` 等被新结果契约完全替代的类型
- `ContextFrame.toOrchestratorContext()` 及核心 `Map<String, Object>` 适配路径
- `AgentLoopOrchestrator.buildSystemPromptMessage()`
- AgentLoop 构造期固定 `effectiveToolSpecs`
- `prompt.assembly` span 及其专用测试
- 影子比较器及只服务迁移期的兼容代码

`VehicleStatusPreProcessor`、`TimeContextPreProcessor`、`MemoryPreProcessor` 不能按名称直接删除；先确认 scene、vision/VL 或其他非 TEXT 配置是否仍使用。若仍有调用者，只移除 TEXT 注册，不改变非 TEXT 行为。

### 2.5 空白记忆执行者必须遵守的强制契约

本节是实现约束，不是示例。执行者不得因为当前代码没有对应类型而自行改变以下接口责任；若源代码在执行前发生变化，应保持语义等价，并在阶段报告中记录实际签名。

#### 2.5.1 Context 入口契约

计划新增的接口应采用以下语义签名：

```java
public interface ContextPreparer {
    ContextPrepareResult prepare(RequestSession session,
                                 ContextCancelChecker cancelChecker);
}

public interface ContextAssemblyGateway {
    ContextAssemblyResult assemble(ContextAssemblyRequest request);
}
```

- `prepare()` 每个 RequestSession 只成功执行一次；不得读取本轮后续会变化的车辆状态或 Session ChatMemory。
- `assemble()` 每次模型调用前执行一次；iteration 从 0 开始，与 AgentLoop 当前迭代完全一致。
- `ContextCancelChecker` 使用 Runtime 已有取消来源的窄接口，不让 Context 依赖 `ActiveRequestRegistry` 具体类。
- `RuntimeCancelChecker` 仍是 AgentRuntime 持有的真实取消来源，负责 Context prepare 之后、AgentLoop 之前以及 Runtime 自身检查点。
- `ContextCancelChecker` 只是 Context 模块可见的窄适配接口，负责 prepare/assemble 内部检查点；AgentRuntime 用 `contextSession -> runtimeCancelChecker.isCancelled(contextSession)` 构造它。
- 两个接口读取同一个取消事实，不维护两份状态；ContextOrchestrator 不持有、查询或导入 `ActiveRequestRegistry`。
- `prepare()` 或 `assemble()` 返回失败对象，不以 null 表示失败；取消返回稳定 CANCELLED 状态。
- ContextOrchestrator 实现上述两个接口；AgentRuntime 只持有 ContextPreparer，AgentLoop 只持有 ContextAssemblyGateway。

`runtime/AgentExecutor.java` 在 Phase 3 直接改为以下唯一抽象方法，不保留两个可选 SAM：

```java
@FunctionalInterface
public interface AgentExecutor {
    AgentResult execute(RequestSession session,
                        ContextPrepareResult prepareResult);
}
```

- 同一 Phase 内更新 AgentRuntime、AIAgentService 和全部 runtime 单测 lambda，确保编译始终闭合。
- Phase 3 的 Service lambda 在方法体内调用旧 `textOrchestrator.execute(session.userInput(), prepareResult.frame().toOrchestratorContext(...))`，仅作为明确的迁移桥。
- Phase 5 将该 lambda 改为 `textOrchestrator.execute(session, prepareResult)`，停止 Map 转换。
- Phase 6 删除 `ContextFrame.toOrchestratorContext()`；AgentExecutor 本身保留，不需要创建第二个临时 Executor 接口。

#### 2.5.2 ContextPrepareResult 与 ContextFrame

`ContextPrepareResult` 至少包含：

- `status`：SUCCESS、FAILED、CANCELLED。
- `frame`：成功时非空，失败时为空。
- `currentUserMessage`：由 UserInput Provider 创建的唯一 LangChain4j UserMessage。
- `cancelChecker`：Runtime 传入的请求级取消检查器，AgentLoop 每轮组装继续复用；不得放入 ContextFrame 或模型消息。
- `providerOutcomes`：静态 Provider 的稳定结果列表。
- `errorCode/errorDetail`：失败时必填。

最终 `ContextFrame` 只保存请求级不变量：

- requestId、clientMessageId、userId、sessionId、personaId、inputType。
- 原始/规范化用户输入的受控引用；生产 Trace 不输出正文。
- intentResult、toolGroupSelectionResult。
- 请求级 Contribution：Prompt、Current User、LongTermMemory、CallerExtra、Tool、Trace-only metadata。
- 不保存 Vehicle、Time、SessionMemory 的快照，不保存每轮预算结果。
- 不保存 `mode`、`renderedExtraContext`、`memorySummary` 等迁移字段。

#### 2.5.3 当前 UserMessage 唯一写入规则

这是防止重复消息的强制顺序：

1. UserInput Provider 在 `prepare()` 中依据 `session.userId()` 和原始输入创建唯一 `UserMessage`；speaker 格式继续使用 `SpeakerMessageFormatter.formatUserMessage(userId, input)`。
2. `ContextPrepareResult.currentUserMessage()` 返回这个对象。
3. AgentLoop 在进入 iteration 0 前，将该对象写入当前 session ChatMemory 一次。
4. iteration 0 的 SessionMemory Provider 读取 ChatMemory，此时快照已经包含当前 UserMessage。
5. Assembler 只从 SessionMemory MessageContribution 放入该消息，不再单独追加 UserInput Contribution。
6. iteration 1 及以后禁止再次写入当前 UserMessage。

因此，UserInput Contribution 同时承担“Context 生成模型消息”和“交给 AgentLoop 持久化”的职责，但最终消息列表只能经 SessionMemory 动态快照出现一次。

空字符串输入：TEXT 正常请求若 input 为空，应由 Runtime/现有请求校验拒绝；Context 不创建空 UserMessage。非 TEXT 空输入不属于本轮。

#### 2.5.4 ContextAssemblyRequest 准确字段

`ContextAssemblyRequest` 至少包含：

- `ContextFrame frame`。
- `int iteration`。
- `List<ChatMessage> sessionMessages`：调用前取得的不可变快照。
- `AgentLoopContext loopContextView` 的只读替代对象，至少暴露上一轮工具执行摘要和安全 veto 状态；禁止把可变 AgentLoopContext 直接交给 Provider。
- `ContextBudgetPolicy budgetPolicy`。
- `ContextCancelChecker cancelChecker`。
- `boolean persistentCompressionAlreadyAttempted`：保证单请求最多压缩一次。

不要在 Request 中传 PromptManager、MemoryOrchestrator、ToolRegistry 等服务；这些是 Orchestrator/Provider 构造依赖。

#### 2.5.5 ContextAssemblyResult 准确字段

成功结果至少包含：

- 不可变 `List<ChatMessage> messages`。
- 不可变 `List<ToolSpecification> toolSpecifications`。
- `ContextBudgetReport budgetReport`。
- `ContextAssemblyDebugInfo debugInfo`。
- `boolean memoryCompacted`。
- `boolean chatMemoryReloadRequired`。
- `List<ContextProviderOutcome> providerOutcomes`。

失败结果至少包含 `ContextErrorCode` 和安全错误详情，并且 `messages/toolSpecifications` 返回空列表。AgentLoop 不得使用失败结果中的任何候选输入。

#### 2.5.6 错误码与现有 AgentResult 映射

新增 `context/ContextErrorCode.java`，固定包含：

- `REQUIRED_PROVIDER_FAILED`
- `TOOL_SPEC_RESOLUTION_FAILED`
- `MESSAGE_SEQUENCE_INVALID`
- `CONTEXT_BUDGET_EXCEEDED`
- `MEMORY_COMPACTION_FAILED`
- `CONTEXT_CANCELLED`
- `CONTEXT_INTERNAL_ERROR`

修改 `AgentResult.ErrorType`，增加 `INVALID_INPUT`、`CONTEXT_BUILD_FAILED` 和 `CONTEXT_BUDGET_EXCEEDED`。映射规则：

- Runtime 在 Context 前发现空 TEXT -> `AgentResult.ErrorType.INVALID_INPUT`。
- budget exceeded -> `AgentResult.ErrorType.CONTEXT_BUDGET_EXCEEDED`。
- cancelled -> 现有 `INTERRUPTED`，再由 Runtime 映射为 cancelled 终态。
- 其他 required Context 错误 -> `CONTEXT_BUILD_FAILED`。
- optional Provider 降级不生成 AgentResult error。
- 不得把 Context 失败统一误报成 `MODEL_CALL_FAILED`，因为此时模型尚未调用。

#### 2.5.7 TEXT AgentLoop 最终公开签名

Phase 5 后 TEXT 使用以下独立构造和执行入口：

```java
public AgentLoopOrchestrator(AgentConfig config,
                             MemoryOrchestrator memoryOrchestrator,
                             ContextAssemblyGateway contextAssemblyGateway)

public AgentResult execute(RequestSession session,
                           ContextPrepareResult prepareResult)
```

- TEXT 构造器不接收 Android Context、PromptManager 或 `List<ToolSpecification>`。
- scene/VL 暂时继续使用原 legacy 构造器和 `execute(String, Map)`，本轮不强行迁移；两个入口必须由 Factory/Service 明确选择，不能在 TEXT 入口内部 fallback 到 legacy。
- `fallbackChatMemory` 仅属于 legacy 构造路径；TEXT 构造器不得创建无 session 的 fallback ChatMemory。
- TEXT execute 要求 `config.memoryPolicy()==PERSISTENT`、sessionId 非空、prepareResult 成功；不满足直接返回 INVALID_CONFIG/CONTEXT_BUILD_FAILED。
- Phase 6 可以保留 legacy 构造器供非 TEXT 使用，但其 JavaDoc 必须标明禁止 TEXT 调用。

### 2.6 Provider 固定顺序、必需性和输出区域

| 顺序 | Provider | 生命周期 | required 规则 | 输出区域 |
|---|---|---|---|---|
| 1 | Runtime | REQUEST_STATIC | request/session identity 缺失时 required failure | TRACE_ONLY |
| 2 | Persona | REQUEST_STATIC | persona 空时规范化为 chat，不失败 | POLICY_ONLY |
| 3 | Prompt | REQUEST_STATIC | 始终 required | SYSTEM |
| 4 | UserInput | REQUEST_STATIC | TEXT 输入非空时 required | CURRENT_USER，先写 ChatMemory |
| 5 | Intent | REQUEST_STATIC | router 已兜底 UNKNOWN，不失败 | POLICY_ONLY/TRACE_ONLY |
| 6 | Tool | REQUEST_STATIC | 明确名称解析失败时 required；CHAT 可为空 | TOOL_SPECIFICATIONS |
| 7 | LongTermMemory | REQUEST_STATIC | optional | CONTEXT_DATA |
| 8 | CallerExtra | REQUEST_STATIC | optional、UNTRUSTED_DATA | CONTEXT_DATA |
| 9 | SessionMemory | ITERATION_DYNAMIC | persistent TEXT 中 required | SESSION_MESSAGES |
| 10 | VehicleState | ITERATION_DYNAMIC | ToolGroup requiredContextKeys 含 vehicle_status 时 required，否则 optional | CONTEXT_DATA |
| 11 | Time | ITERATION_DYNAMIC | optional | CONTEXT_DATA |

装配顺序不按 Provider 注册顺序直接拼接，而固定为：SystemMessage -> 合并后的 Context Data UserMessage -> Session ChatMemory。POLICY_ONLY 和 TRACE_ONLY 永不进入消息列表。

Context Data 合并格式使用稳定标题和来源块，不接受 Provider 自己拼角色指令：

```text
【上下文数据】
【长期记忆】
...
【车辆状态】
...
【当前时间】
...
【调用方附加信息】
...
```

只生成一条 Context Data UserMessage；没有可见数据时不生成空消息。CallerExtra 中出现“忽略系统指令”等文本仍作为数据原样转义/包裹，不能改变 SystemMessage。

### 2.7 Tool 解析和 Demo fallback 的确定性算法

Tool Provider 必须按以下顺序执行：

1. `ToolGroupSelectionResult` 表示 CHAT_ONLY：返回空 ToolContribution，成功。
2. `allToolsFallback=true`：调用 `ToolRegistry.enabledToolSpecifications()`，按 ToolRegistry 注册顺序返回全部 specs。
3. 否则读取 `selectedToolNames`；去除重复名称但保留首次出现顺序。
4. 调用 `toolSpecificationsByNames()` 严格解析。
5. 任一名称缺失：返回 `TOOL_SPEC_RESOLUTION_FAILED`，不得改走全量。
6. 计算工具集合 schema 摘要供缓存/Trace 使用。

`ToolRegistry.registerAll()` 还应拒绝同名工具覆盖：当前 `dispatchers.put()` 会静默覆盖 dispatcher，而 specs 仍可能重复。增加重复名称校验并在注册阶段抛出 `IllegalArgumentException`，对应单元测试必须先失败后修复。

全量 fallback 的工具集合在单次请求内固定。Tool Calling 第二轮不得重新运行 ToolGroupSelector，也不得因预算压力随机丢弃部分工具。

### 2.8 Memory 快照和压缩后的 ChatMemory 重载协议

`MemorySnapshot.sessionId()` 必须返回原始 sessionId。当前构造器将其再次转换为 `memory:<sessionId>`，Phase 2 必须修正；只有 SessionMemoryStore/ChatMemoryStore 边界调用 `SessionMemoryIds.shortTermMemoryId()`。

`LongTermMemorySnapshot` 固定包含：

- 原始 `userId`。
- 按 Category、key 稳定排序的不可变 `List<MemoryEntry>`。
- `contentVersion`：由条目稳定字段计算，用于 token estimate cache，不使用随机 UUID。

压缩写回协议固定为：

1. Context 计算 `targetMemoryTokens`，调用 `MemoryOrchestrator.planSessionCompaction(sessionId, targetMemoryTokens)`。
2. Phase 4 生产影子链路只接收 MemoryCompactionPlan，不调用模型、不写 Store；Phase 5 Context 独占链路才调用 `executeCompactionPlan(plan, traceParent)`。
3. MemoryCompressor 在 execute 阶段返回新的完整消息列表和压缩诊断，不直接写 Store。
4. `SessionMemoryStore` 新增 `replaceMessagesOrThrow(Object memoryId, List<ChatMessage> messages)`，使用一次 `INSERT OR REPLACE` 写完整 JSON；发生 SQLite/序列化异常时抛出新的 `MemoryPersistenceException`，不得复用当前会吞异常的 `updateMessages()`。
5. SessionChatMemoryProvider 调用 `replaceMessagesOrThrow(memoryId, completeList)` 执行完整持久化替换。
6. Store 成功后从 provider cache 移除旧 ChatMemory；Store 失败则不动 cache。
7. `MemoryCompactionResult` 返回 `reloadRequired=true`。
8. Context 重新调用 MemoryOrchestrator 读取新快照并重新装配。
9. `ContextAssemblyResult.chatMemoryReloadRequired=true`。
10. AgentLoop 在调用模型前重新执行 `chatMemoryForSession(sessionId, maxMessages)`，替换本地旧引用；之后 AiMessage/ToolResult 只写新实例。

不得在旧 ChatMemory 对象上执行 `clear()+add()` 来模拟原子替换，也不得在 Store 更新后继续向旧引用写入。

`MEMORY_COMPACTION_FAILED` 只有在 Memory 是主要压力且 required 内容最终无法满足预算时才升级为失败；若移除 optional 内容后仍可满足预算，记录 compression failure 后继续。

### 2.9 Demo Token 预算固定配置

为了避免执行者自行选择数值，本计划为当前 qwen-turbo Demo 固定保守配置：

- `maxContextTokens = 32768`
- `reservedOutputTokens = 2048`
- `safetyMarginTokens = 1024`
- `maxInputTokens = 29696`

这些值集中定义在 `AgentConfigFactory` 的 TEXT 模型配置或独立 `ModelContextWindowProfiles.qwenTurboDemo()`，禁止散落到 Provider、MemoryCompressor 或 Service。测试必须注入更小预算验证边界，不直接依赖 32768。

后续若确认部署模型窗口变化，只替换 profile，不改变 ContextBudgetManager 算法。本轮不修改 Gradle/local.properties 来承载这些值。

因此这三个数值是可替换的 Demo 配置，不是写死的算法常量。后期调整时只修改 `ModelContextWindowProfiles.qwenTurboDemo()` 的返回值，或在生产装配中注入另一个 profile；Provider、Assembler、BudgetManager、MemoryCompressor 和测试算法不需要修改。

首版估算规则也必须确定：

- 普通文本：`ceil(codePointCount / 2.0)`，至少 1 token。
- 每条 ChatMessage 增加固定结构开销 8 tokens。
- 每个 ToolSpecification：name、description、parameter schema 序列化后的 code point 总数按 `/2` 估算，再增加 16 tokens 结构开销。
- 最终估算乘以 1.15 安全系数并向上取整。
- cache 只缓存 System Prompt、长期记忆块和 Tool Schema；Session messages、时间、车辆状态不缓存。

该估算是保守预算工具，不宣称等于 Qwen tokenizer 真实结果。

### 2.10 影子链路启停和删除规则

- Phase 3 不新增长期 ContextMode。AgentLoop 在代码中无条件执行一次旧装配和一次 shadow assemble；shadow 结果不影响真实请求。
- 为避免 Release 长期双倍开销，Phase 3/4 的迁移提交只用于开发验证分支；若必须打 Demo 包，使用构造注入的临时 `ContextShadowRecorder` 是否为空决定是否比较，不新增 BuildConfig 或远程开关。
- Phase 5 切换时，同一提交把真实 ChatRequest 改为 Context result，并停止旧装配参与模型输入；shadow comparator 可继续比较一次旧候选，但旧候选不得调用 PreProcessor 中具有副作用的实现。
- Phase 6 完全删除 comparator、recorder、旧候选构造和相关构造参数。最终仓库不得存在 `shadowEnabled`、`legacyEnabled` 等运行开关。

### 2.11 Trace 父子上下文的准确创建位置

为实现已确认的层级，span 创建责任固定如下：

1. AIAgentService 已有 `agent.request` root span 保持不变。
2. `AgentRuntime.execute(RequestSession)` 在调用 ContextPreparer 前创建 `agent.loop` span，并使其成为当前 scope。
3. Runtime 在该 scope 内依次执行 `contextPreparer.prepare()` 和 `agentExecutor.execute()`；无论成功、失败或取消都在 finally 关闭 scope/end span。
4. ContextPreparer 以当前 `agent.loop` Context 为 parent 创建 `context.prepare`。
5. AgentLoop 每轮在同一 loop parent 下创建 `context.assemble`、`gen_ai.chat` 和 `tool.execute`；这些 span 是兄弟节点，memory.read/compress 挂在对应 context span 下。
6. AgentLoop 不再额外创建第二个 `agent.loop` span。

`TraceSession` 应增加显式 parent 版本：`startChildSpan(String, io.opentelemetry.context.Context)`，并让 LLM/Tool recorder 接受 parent 或在正确 scope 下使用 `Context.current()`。禁止继续无条件使用 rootContext。

### 2.12 每阶段实施纪律

空白记忆执行者在每个 Phase 必须按以下顺序工作：

1. 先读取本 Phase 列出的生产文件和已有测试，不从本文假设文件内容未变化。
2. 运行本阶段基线测试并保存结果；基线本身失败时停止，不把已有失败归因于新改动。
3. 每个 Task 先写失败测试，再做最小实现，再运行目标测试。
4. Task 内不得提前实现后续 Phase 的生产切换。
5. Phase 结束运行本计划给出的完整阶段命令，并执行 git diff 范围审计。
6. 输出阶段报告：实际修改文件、与计划签名的差异、测试结果、遗留风险、是否满足阶段退出状态。
7. 未满足阶段门禁时停止，不能通过放宽断言、删除测试或启用全量 fallback 掩盖错误。

---

## 3. Phase 1：基线测试与 Context 强类型骨架

**阶段目标：** 固化旧链路真实行为，建立新契约和纯消息装配器，但不改变生产模型输入。

### 3.1 本阶段工作边界

**本阶段允许修改：**

- Context 领域模型、Provider 返回契约和纯装配器测试。
- `ContextFrame`、`ContextFrameBuilder` 的内部数据结构，但必须保留当前 getter 和 `toOrchestratorContext()` 兼容行为。
- 测试侧 ModelCaller、ChatMemory 和 ToolSpecification 捕获工具。
- TraceSession/AgentTraceRecorder 新增不改变旧行为的 parent-aware 重载及层级单测。

**本阶段禁止修改：**

- 不修改 `AgentRuntime.execute()` 调用顺序。
- 不修改 `AgentLoopOrchestrator.execute(String, Map<String,Object>)` 的生产输入和 ChatRequest 构造结果。
- 不移除 `ContextMode`、`ContextSection`、旧 Provider、旧 PreProcessor 或固定工具规格。
- 不调用真实模型、不执行真实车控、不写生产 SQLite。
- 不接入预算裁剪、Memory 压缩和新 Context span；Trace 只建立 parent-aware API，不切换旧调用点和层级。

**阶段退出状态：** 新旧领域契约可以同时编译；生产仍完全走旧链路；新 `ContextMessageAssembler` 只能被单元测试调用。

### 3.2 原代码具体修改点

| 原文件和位置 | 当前行为 | 本阶段如何修改 |
|---|---|---|
| `context/ContextProvider.java` 的 `type()`、`provide(...)` | Provider 固定返回一种 `ContextSectionType` 和单个 Section | 保留旧方法供适配器使用，同时引入 Contribution 返回契约；Provider 名称与生命周期成为显式元数据，不再由 Section 类型隐式推断 |
| `context/ContextProviderResult.java` | 只有 `success/fallback/failure + ContextSection` | 增加不可变 Contribution 列表、provider outcome、错误码和诊断 metadata；迁移期提供从旧 Section 构造结果的静态工厂 |
| `context/ContextFrame.java` 构造器和字段 | 同时保存 memorySummary、vehicleStateSnapshot、renderedExtraContext、sections 等多份事实 | 新增 `contributions` 作为唯一新数据源；旧字段由 Builder 从 Contribution 派生，只读保留，禁止调用者分别写入互相矛盾的值 |
| `context/ContextFrameBuilder.java` | 可分别设置 mode、各类字符串和 sections | 增加 `contributions(...)` 和一致性校验；若同时传旧字段和新 Contribution，测试阶段必须检测冲突，不能静默覆盖 |
| `context/ContextBudgetManager.java` | 以字符数裁剪字符串 | 本阶段不改生产裁剪，只抽取未来 BudgetManager 需要消费的消息/工具输入结构，确保 Phase 4 可替换实现 |

### 3.3 新增文件的职责和接口形态

- `ContextContribution` 设计为不可变接口，只定义 `sourceKey()`、`visibility()`、`trustLevel()`、`priority()`、`lifecycle()`、`required()` 和 metadata，不定义通用 payload。
- `TextContextContribution` 明确文本目标区域：SYSTEM 或 CONTEXT_DATA；它不直接创建 LangChain4j 消息，转换由 Assembler 负责。
- `MessageContextContribution` 直接承载不可变 `List<ChatMessage>`，并标记消息来源是 CURRENT_USER 或 SESSION_MEMORY。
- `ToolContextContribution` 承载不可变 `List<ToolSpecification>` 以及 selection mode，不保存 ToolDispatcher 或执行函数。
- `ContextAssemblyRequest` 不引用 Android Context；只传 `ContextFrame`、迭代状态、动态 Contribution 和预算输入，保证 JVM 单测可独立执行。
- `ContextAssemblyResult` 的成功结果必须同时返回 messages、toolSpecifications、budgetReport 和 debugInfo；失败结果不得带可发送给模型的半成品列表。
- `ContextMessageSequenceValidator` 作为 Assembler 的必经后置校验，不由 AgentLoop 额外调用来修补结果。

### Task 1.1：补齐旧链路特征测试

**测试文件：**

- Create: `app/src/test/java/com/hirain/aiagent/core/CapturingModelCaller.java`
- Create: `app/src/test/java/com/hirain/aiagent/context/LegacyTextInputCharacterizationTest.java`
- Modify: `AgentLoopOrchestratorSessionMemoryTest.java`
- Modify: `AgentLoopOrchestratorContextInjectionTest.java`
- Modify: `AgentLoopOrchestratorTraceTest.java`

**实施步骤：**

- [ ] 实现只捕获 `ChatRequest`、不访问网络的 `CapturingModelCaller` 测试工具。
- [ ] 固化人格 System Prompt、长期记忆、当前用户输入、短期会话记忆、车辆状态、时间和 caller extra 的旧顺序。
- [ ] 固化首次模型调用与 Tool Calling 第二轮的消息类型和数量。
- [ ] 固化 session 切换不读取其他 session ChatMemory，用户切换仍保留当前 session 对话但切换长期记忆的目标行为。
- [ ] 固化当前固定工具规格行为，仅作为影子比较基线，不作为最终正确性标准。
- [ ] 给每项基线标注“必须保持”或“允许变化”，允许变化仅包括内部 ID 不再注入、长期记忆角色调整、工具集合动态收敛和重复上下文消失。
- [ ] 生成 `docs/testresult/context-shadow-baseline-matrix.md`，保存后续影子比较的具体基线；该文档是 Phase 3 的输入，不是可选总结。

基线矩阵必须覆盖以下 8 个场景，并逐项记录“旧消息序号/类型/来源摘要、旧工具名集合、目标新消息序号/类型/来源摘要、目标工具名集合、允许变化原因”：

1. 普通 CHAT、无长期记忆、无 caller extra。
2. 明确 AC ToolGroup，包含车辆状态和时间。
3. 模糊车控进入 allToolsFallback，旧/新均为全量工具。
4. 存在长期记忆，旧链路把它拼入 System，新链路移到 Context Data。
5. caller extra 含普通数据及疑似指令文本，验证其从临时消息收敛为 UNTRUSTED Context Data。
6. 同一 session 第二轮普通对话，验证历史和当前 UserMessage 顺序。
7. Tool Calling 第二次模型调用，包含 Ai tool request 与全部 ToolResult。
8. session 切换和 user 切换，分别验证短期隔离、长期记忆切换。

矩阵不得保存完整敏感正文；测试中保留内存断言，文档只保存脱敏摘要/hash 和固定测试文本。

### Task 1.2：建立 Contribution 和生命周期契约

**生产文件：** 创建第 2.2 节中的 Contribution、Visibility、Trust、Lifecycle、Priority 类型；修改 `ContextProvider` 与 `ContextProviderResult`。

**实施步骤：**

- [ ] 先为每个枚举和 Contribution 类型编写不可变性、空值校验和集合防御复制测试。
- [ ] `ContextProviderResult` 改为返回 Contribution 列表、provider outcome 和诊断信息，不再只返回单个文本 Section。
- [ ] 每个 Contribution 必须显式声明 source key、visibility、trust、priority、lifecycle、required 和稳定 metadata。
- [ ] 禁止使用通用 `Object payload`；文本、消息、工具必须由不同强类型承载。
- [ ] `ContextFrame` 的 canonical source 改为不可变 Contribution 集合；旧 convenience 字段只作为迁移期只读 adapter，禁止双向写入。
- [ ] 明确请求级 Contribution 只能准备一次，迭代级 Contribution 不写入长期 ContextFrame 缓存。

### Task 1.3：实现纯 ContextMessageAssembler

**生产文件：** 创建 `ContextAssemblyRequest/Result`、`ContextMessageAssembler`、`ContextMessageSequenceValidator`。

**测试文件：**

- Create: `ContextMessageAssemblerTest.java`
- Create: `ContextMessageSequenceValidatorTest.java`

**实施步骤：**

- [ ] 先写失败测试：两个 SystemMessage、Context Data 位于 System 之前、当前用户消息缺失、孤立 Tool Result、Tool Schema 名称重复。
- [ ] 固定最终顺序：唯一 SystemMessage -> 可选 Context Data UserMessage -> Session ChatMemory（含当前 UserMessage 和工具交换）。
- [ ] SystemMessage 只接受 TRUSTED_SYSTEM 且目标为 SYSTEM 的 Contribution。
- [ ] 长期记忆、车辆状态、时间和 caller extra 统一进入 Context Data，不允许伪装 System 指令。
- [ ] 消息装配器只转换和排序输入，不读取外部模块、不写 Trace、不压缩 Memory。
- [ ] ToolSpecification 按稳定顺序去重，发现同名不同 schema 时明确失败。

### Task 1.4：提前建立 Trace parent-aware 基础 API

**工作量与风险：** 中等偏高。该 Task 不改变现有 span 名称和父子行为，只建立后续可用的 parent-aware API，降低 Phase 4 同时改 Context、Budget、Memory、Trace 的集中风险。

**生产文件：**

- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`

**测试文件：**

- Create: `app/src/test/java/com/hirain/aiagent/trace/TraceSessionParentChildTest.java`
- Modify: `TraceSessionRootTest.java`
- Modify: `AgentTraceRecorderTest.java`

**实施步骤：**

- [ ] `TraceSession` 新增显式 parent Context 的 `startChildSpan`，以及 LLM/Tool 的 parent-aware 重载。
- [ ] 旧无 parent 方法继续以 rootContext 为父，保证 Phase 1 生产 Trace 完全不变。
- [ ] `AgentTraceRecorder` 增加接受 parent Context 的 Prompt/LLM/Tool/Memory 重载；本阶段旧 AgentLoop 仍调用旧重载。
- [ ] `TraceSessionParentChildTest` 构造 root -> loop -> child，直接断言 parentSpanId，而不是只断言 span 名存在。
- [ ] 测试显式 parent、默认 root parent、scope 关闭和异常结束四种情况。

当前仓库不存在 `LlmTraceRecorder.java` 或 `ToolTraceRecorder.java`，不得按审查文档名称创建重复 recorder；LLM、Tool、Memory 的真实入口都在 `AgentTraceRecorder.java`。

### Phase 1 验收

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.trace.TraceSessionParentChildTest" --tests "com.hirain.aiagent.trace.AgentTraceRecorderTest"
```

预期：新契约和装配器测试通过；8 个旧链路场景及基线矩阵完成；parent-aware API 测试通过；旧调用仍保持 root parent；生产模型输入和 Trace 行为没有变化。

---

## 4. Phase 2：Provider 真实化与能力模块读接口

**阶段目标：** Context 获得真实 Prompt、Memory、Vehicle、Time 和 Tool 数据，旧链路仍是唯一真实模型输入写入者。

### 4.1 本阶段工作边界

**本阶段允许修改：**

- `ContextBuildInput` 的依赖装配字段，以及 Context Provider 对能力模块只读 API 的调用。
- Prompt、Memory、ToolRegistry 为 Context 增加窄范围只读接口。
- `ContextOrchestrator.defaultForText()` 的 Provider 注册和请求级/迭代级分类。

**本阶段禁止修改：**

- Provider 不得直接修改 ChatMemory、长期记忆、车辆状态或 ToolRegistry。
- Prompt Provider 不得自行读取 assets 或复制 PromptManager 的模板渲染实现。
- Tool Provider 不得执行工具，也不得根据 ToolGroup 名称手工伪造 ToolSpecification。
- SessionMemory Provider 不得触发压缩；LongTermMemory Provider 不得写回提取结果。
- 新 Provider 结果仍不进入真实 ChatRequest，不能提前移除旧 PreProcessor。

**阶段退出状态：** 所有 Context 来源都已经是真实数据，但只供影子装配准备；能力模块仍是数据和算法所有者。

### 4.2 原代码具体修改点

| 原文件和位置 | 当前行为 | 本阶段如何修改 |
|---|---|---|
| `ContextBuildInput.java` Builder | 保存 mode、ToolGroupRegistry、PromptManager、MemoryOrchestrator、VehicleStatusProvider、TimeProvider | 增加 ToolRegistry、ContextPolicy、BudgetPolicy、TokenEstimator；SpeakerMessageFormatter 是静态纯格式器，由 UserInput Provider直接调用，不放入依赖容器；不把 AIAgentService 或 AgentLoop 整体传给 Provider |
| `ContextOrchestrator.defaultForText()` | 固定注册 9 个 Provider，随后全部在一次 `build()` 中执行 | 拆成 request-static 与 iteration-dynamic 两个有序列表；`prepare()` 只运行静态 Provider，`assemble()` 时再运行 SessionMemory、Vehicle、Time |
| `ContextOrchestrator.build()` | 收集 Section、字符裁剪、拼 `renderedExtraContext`、提取多个字符串字段 | 迁移期保留旧 `build()`，内部改为调用新的 `prepare()` 后再生成旧兼容结果；新路径不得再依赖 `findFirstContent()` 提取业务事实 |
| `PromptContextProvider.java` | 当前只形成 Prompt 观测 Section | 调用 PromptManager 的既有选择/渲染入口，使用 personaId 和请求上下文得到真实 System Prompt Contribution；渲染失败标为 required failure |
| `MemoryContextProvider.java` | 当前没有同时表达长期和短期记忆的真实生命周期 | 拆分为 `LongTermMemoryContextProvider` 与 `SessionMemoryContextProvider`；旧类只保留适配到拆分实现，Phase 6 删除 |
| `VehicleStateContextProvider.java`、`TimeContextProvider.java` | HYBRID 模式下主要观测，实际消息仍由 PreProcessor 注入 | 删除 Provider 内 mode 分支，返回真实动态 Contribution；优先级由 ContextPolicy 根据 intent 决定 |
| `ToolGroupContextProvider.java` | 把 group/toolName 信息渲染成文本 | 改造为 Tool Context Provider：读取 selection result，再从 ToolRegistry 解析真实 specs；ToolGroup 描述仅留 Trace metadata |
| `UserInputContextProvider.java` | 提供用户输入 Section | 生成唯一 CURRENT_USER MessageContribution，并复用现有 SpeakerMessageFormatter 的 speaker 格式，不在 AgentLoop 再格式化一次 |

### 4.3 能力模块需要新增的窄接口

- `MemoryOrchestrator.longTermMemorySnapshot(String userId)`：只读返回稳定条目，不能调用 `prepareSystemPrompt()` 产生角色混合字符串。
- `MemoryOrchestrator.sessionMemorySnapshot(String sessionId, int maxMessages)`：读取当前 session 的 ChatMessage 副本，不把内部可变 ChatMemory 暴露给 Assembler。
- `ToolRegistry.toolSpecificationsByNames(List<String>)`：保持输入顺序，缺失名称单独返回或抛出明确领域异常，不自动回退全量。
- `ToolRegistry.enabledToolSpecifications()`：仅供已经确认 `allToolsFallback=true` 的分支使用，返回注册表当前真实全量。
- Prompt Provider 使用 `PromptConstants.textPersonaTemplateName(session.personaId())` 选择模板，并调用现有 `PromptManager.render(templateName, variables)`；本轮不新增 PromptManager 组合入口，也不复制模板缓存逻辑。
- VehicleStatusProvider 和 TimeProvider 沿用现有接口；Context 只决定调用时机和模型可见性。

### 4.4 Provider 失败边界

- Prompt、Current User、Session Memory 读取结构错误、明确工具名称无法解析属于 required failure，Context 不得构造可发送结果。
- 长期记忆、车辆状态、时间、caller extra 属于可降级来源；失败后记录 outcome，但不得注入错误文本冒充上下文。
- `allToolsFallback=true` 是正常选择结果，不记为 Provider failure；它必须显式进入 ToolContribution selection mode 与 Trace metadata。
- Provider 抛出的底层异常在 Context 边界转换为稳定错误码，诊断可保留异常类型，但生产 Trace 不记录敏感正文。

### Task 2.1：拆分请求级与迭代级 Provider

**Provider 生命周期：**

| Provider | 生命周期 | 模型可见内容 |
|---|---|---|
| Runtime | REQUEST_STATIC | 默认 Trace-only |
| Persona | REQUEST_STATIC | 通过 Prompt 渲染进入唯一 SystemMessage |
| Prompt | REQUEST_STATIC | 渲染后的 System Prompt |
| UserInput | REQUEST_STATIC | 当前 UserMessage |
| Intent | REQUEST_STATIC | 默认 policy/trace；不直接注入内部理由 |
| Tool | REQUEST_STATIC | ToolSpecification |
| LongTermMemory | REQUEST_STATIC | Context Data 中的完整记忆条目 |
| CallerExtra | REQUEST_STATIC | 不可信 Context Data，可选 |
| SessionMemory | ITERATION_DYNAMIC | 当前 session ChatMemory 快照 |
| VehicleState | ITERATION_DYNAMIC | 根据意图动态提升优先级 |
| Time | ITERATION_DYNAMIC | 普通 Context Data，可选 |

**实施步骤：**

- [ ] `PromptContextProvider` 调用现有 PromptManager 选择和渲染模板，输出受信 System Contribution。
- [ ] `UserInputContextProvider` 使用现有 SpeakerMessageFormatter 生成当前 UserMessage，避免 AgentLoop 重复格式化。
- [ ] `RuntimeContextProvider`、`IntentContextProvider` 和 `PersonaContextProvider` 只输出策略或 Trace 所需元数据，除非 Prompt 渲染明确消费它们。
- [ ] 将旧 `MemoryContextProvider` 拆成 LongTermMemory 与 SessionMemory 两种真实语义，避免将两种生命周期混在一起。
- [ ] 新增 CallerExtra Provider；将外部文本标记为 UNTRUSTED_DATA、OPTIONAL，不能进入 SystemMessage。
- [ ] Vehicle 与 Time Provider 去掉 HYBRID 下不可渲染分支，按迭代实时读取。
- [ ] ContextOrchestrator 分别维护 request-static 和 iteration-dynamic Provider 链，保持注册顺序确定。

### Task 2.2：为 Memory 增加稳定读接口

**生产文件：** 修改 `MemoryOrchestrator` 和 `MemorySnapshot`，创建 `LongTermMemorySnapshot`；`MemorySnapshot.sessionId()` 明确改为返回原始 sessionId。

**实施步骤：**

- [ ] 增加按 `userId` 读取长期记忆稳定快照的接口，返回完整条目而不是已拼进 System Prompt 的字符串。
- [ ] 保留 `prepareSystemPrompt()` 供迁移期旧链路使用，但新 Provider 禁止调用它；最终切换后删除或降级为非 TEXT 专用接口。
- [ ] SessionMemory Provider 通过 `SessionChatMemoryProvider` 读取当前 session 消息快照，不创建其他 session 的隐式共享键。
- [ ] 统一 `sessionId` 与 `SessionMemoryIds.shortTermMemoryId(sessionId)` 语义：领域快照保存原始 sessionId，只有 Store/ChatMemory 边界生成 memoryId。
- [ ] 修改前全仓搜索 `MemorySnapshot`、`snapshot.sessionId()`、`shortTermMemoryId()` 和 `memoryId`；把实际调用点记录到 Phase 报告，禁止只改 getter 后依赖编译碰运气。
- [ ] 修改 `MemorySnapshotTest.snapshotDefensivelyCopiesMessages()`，明确断言输入 `session-1` 输出仍为 `session-1`；新增 `MemorySnapshotSessionIdBoundaryTest`，验证只有 Store/Provider 边界产生 `memory:session-1`。
- [ ] 当前核验未发现生产调用者依赖错误的 prefixed `MemorySnapshot.sessionId()`，但实施者必须以执行时搜索结果为准；若发现依赖者，改为在其 Store 调用点显式执行 `SessionMemoryIds.shortTermMemoryId(rawSessionId)`。
- [ ] 用户级长期记忆查询只使用 userId；短期记忆查询只使用 sessionId，不用 personaId 重新分裂当前已确认的会话语义。
- [ ] 增加用户切换、session 切换和空记忆测试。

### Task 2.3：让 Tool Provider 解析真实 ToolSpecification

**生产文件：**

- Modify: `ai/langchain4j/tool/ToolRegistry.java`
- Replace or refactor: `context/provider/ToolGroupContextProvider.java`

**实施步骤：**

- [ ] ToolRegistry 增加只读、稳定顺序的按 toolName 查询规格接口；不改变注册和 dispatch 责任。
- [ ] 明确命中时严格解析 `selectedToolNames`，任何名称不存在都返回必需错误，不回退全量。
- [ ] `allToolsFallback=true` 时读取 ToolRegistry 全部已启用规格，记录 fallback 和最高风险，不复用任意空列表。
- [ ] CHAT_ONLY_GROUP 明确返回空工具集合，不能被当成“选择失败”。
- [ ] 生成的 ToolContribution 只包含真实 ToolSpecification，不再把 toolName 列表拼成模型上下文文本。
- [ ] 保持本请求后续 Tool Calling 迭代使用同一工具集合；本轮不实现迭代中动态发现工具。

### Phase 2 验收

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.memory.*" --tests "com.hirain.aiagent.toolgroup.*"
```

预期：所有 Provider 输出真实 Contribution；用户/会话隔离测试通过；工具名称和 ToolSpecification 一致；旧模型输入仍未切换。

---

## 5. Phase 3：影子装配与动态工具差异验证

**阶段目标：** 在每次 AgentLoop 迭代生成新 Context 候选输入，但不发送给模型、不写 ChatMemory，以实际差异证明新链路可切换。

### 5.1 本阶段工作边界

**本阶段允许修改：**

- Runtime 到 AgentLoop 的参数传递方式，可以增加强类型参数和迁移适配器。
- AgentLoop 每轮模型调用前增加一次影子 `assemble()`，以及规范化差异记录。
- 新 Context 链路读取 ChatMemory 快照和循环状态。

**本阶段禁止修改：**

- 真实 `ChatRequest.builder().messages(...)` 仍使用旧 `allMessages`。
- 真实 `toolSpecifications(...)` 仍使用旧 `effectiveToolSpecs`。
- 影子链路不得调用 ChatModel、ToolExecutor、MemoryCompressor 或 `chatMemory.add()`。
- 影子失败不得改变当前请求返回值，但必须形成可查询诊断。
- 不根据一次人工日志观察直接切换真实链路，必须由自动化差异测试覆盖。

**阶段退出状态：** 每次模型迭代都有一份旧输入和一份 Context 候选输入，且未允许差异清零；生产行为仍由旧输入决定。

### 5.2 原代码具体修改点

| 原文件和位置 | 当前行为 | 本阶段如何修改 |
|---|---|---|
| `AgentRuntime.execute(RequestSession)` | 调用 `contextOrchestrator.build(session)`，再把 ContextFrame 交给 AgentExecutor | 改为调用 `prepare(session)` 得到 `ContextPrepareResult`；旧 build result 只在 adapter 内生成，不再成为新接口的主语义 |
| `AgentExecutor.java` | SAM 主入口是 `execute(String, Map<String,Object>)`，default 方法把 ContextFrame 转 Map | 直接把唯一抽象方法改为 `execute(RequestSession, ContextPrepareResult)`；同 Phase 更新所有 lambda。Service 在 Phase 3 lambda 内显式转换到旧 AgentLoop Map，Phase 5 再移除该转换 |
| `ContextFrame.toOrchestratorContext()` | 写入 context_frame、mode、rendered extra、selected tool names 等键 | Phase 3 只用于旧链路；新增代码不得从这些字符串键反解 Context 数据 |
| `AgentLoopOrchestrator.execute(...)` 循环内模型调用前 | 自己得到 `allMessages` 并直接创建 ChatRequest | 在同一位置构造 `ContextAssemblyRequest`，调用 gateway，随后将新结果交给 comparator；旧 ChatRequest 构造保持不变 |
| AgentLoop 工具执行完成后下一轮 | ChatMemory 新增 ToolResult 后直接进入下一轮 | 下一轮 shadow assemble 重新读取当前 ChatMemory 和动态 Provider，验证车辆状态及工具交换已经更新 |

### 5.3 影子比较实现细节

- `ContextShadowComparator` 先将新旧输入规范化，忽略对象实例、metadata 顺序和允许变化内容，比较真正会影响模型的消息与 schema。
- 消息正文不直接写生产日志；比较器输出 message type、source key、长度、hash/摘要和差异原因。
- ToolSpecification 比较 name、description 与 parameter schema 的稳定摘要，不能只比较数量。
- `ContextShadowComparison` 使用明确状态：MATCH、EXPECTED_DIFFERENCE、BLOCKING_DIFFERENCE、NEW_ASSEMBLY_FAILED。
- `ContextShadowRecorder` 首选写入 Trace event 和测试捕获器，不新增 Android 持久化表。
- 新旧消息中当前 UserMessage 的 speaker 格式不同必须先归一化后比较，避免把格式空格误判成架构差异。
- Phase 3 完成报告应列出所有允许差异及对应测试，不能以“整体大致一致”作为通过结论。

### Task 3.1：建立影子装配适配点

**生产文件：**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentExecutor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

**必须同步修改的测试调用点：**

- Rewrite: `app/src/test/java/com/hirain/aiagent/runtime/AgentExecutorCompatibilityTest.java`
  - 删除“新入口委托旧二参 SAM”的旧目标，改为验证 `RequestSession + ContextPrepareResult` 原样传递。
- Modify: `AgentRuntimeTest.java`
  - 更新所有 `(userInput, context)` lambda，改为 `(session, prepareResult)` 并从强类型对象断言。
- Modify: `AgentRuntimeContextTest.java`
  - 删除同时 override 旧/新方法的匿名类，统一实现新 SAM。
- Modify: `AgentRuntimeResolvedSessionTest.java`
  - 将 RecordingExecutor 改为记录 ContextPrepareResult，并继续断言 resolved session。
- Modify: `AgentRuntimeToolGroupTraceTest.java`
  - 保留 ToolGroup trace 断言；若构造 Runtime 时传入 AgentExecutor，统一改为新强类型 lambda。
- Modify: `app/src/test/java/com/hirain/aiagent/trace/AIAgentServiceTraceWiringTest.java`
  - 更新 Service 源码装配断言，确认 lambda 接收 RequestSession/ContextPrepareResult，不再断言旧 Map 参数。

**实施步骤：**

- [ ] `AgentRuntime` 在 RequestSession 固定后执行一次 `ContextPreparer.prepare()`，将 ContextPrepareResult 通过强类型接口传给 TEXT executor。
- [ ] `AgentExecutor` 将唯一 SAM 改为 `execute(RequestSession, ContextPrepareResult)`；同一 Task 更新 Service 和 runtime 测试 lambda，不保留双 SAM adapter。
- [ ] AgentLoop 每次模型调用前创建 `ContextAssemblyRequest`，包含迭代号、当前 ChatMemory 快照和本轮工具执行状态。
- [ ] 新装配结果只进入 Shadow Comparator，不进入 `ChatRequest`，不新增 ChatMemory 消息，也不触发 Memory 压缩。
- [ ] 在所有 Provider 之后、模型调用之前、工具执行之后下一轮开始前设置取消检查点。
- [ ] 在 `AgentRuntimeContextTest` 增加同源取消测试：RuntimeCancelChecker 返回 true 时，传给 Context 的 adapter 也返回 true；ContextCancelChecker 不持有 ActiveRequestRegistry，且 prepare/assemble 取消都不调用 AgentExecutor。

### Task 3.2：实现受控差异比较

**生产/测试辅助文件：**

- Create: `context/ContextShadowComparison.java`
- Create: `context/ContextShadowComparator.java`
- Create: `context/ContextShadowRecorder.java`
- Create: `ContextShadowComparatorTest.java`
- Create: `AgentLoopContextShadowTest.java`

**比较维度：**

- 消息类型、顺序、数量和规范化内容摘要。
- SystemMessage 是否唯一。
- 当前 UserMessage 是否唯一。
- Session ChatMemory 是否来自正确 session。
- Tool Request 与 Tool Result 是否成组。
- 工具名称集合、schema 摘要和 fallback 标识。
- 新旧估算输入大小。

**允许差异：**

- 内部 userId/sessionId/personaId/route reason 不再进入 LLM。
- 长期记忆从 System Prompt 移到 Context Data。
- 重复的车辆、时间和 extra context 被合并。
- 明确 ToolGroup 命中后工具集合从固定全量收敛为选中集合。
- `allToolsFallback=true` 时仍保持全量工具。

任何未列入允许差异表的差异都视为切换阻断项，不得仅记录后继续 Phase 5。

比较器不允许只依据上述定性条目放行。实现时必须读取 Phase 1 生成的 `docs/testresult/context-shadow-baseline-matrix.md`，为 8 个 scenarioId 建立显式规则；每条 EXPECTED_DIFFERENCE 必须包含 scenarioId、旧消息位置、新消息位置、sourceKey 和固定 reason code。没有 scenarioId 的差异一律 BLOCKING_DIFFERENCE。

### Task 3.3：验证多轮动态上下文

- [ ] Fake Model 第一轮返回车辆工具调用，Fake ToolExecutor 修改车辆状态，第二轮 Context 必须读取新状态。
- [ ] 第二轮消息必须包含第一轮 Ai Tool Request 与对应 Tool Result，不能重复插入当前 UserMessage。
- [ ] 同一请求内工具集合保持稳定；明确 ToolGroup 和 allToolsFallback 两条路径分别测试。
- [ ] Shadow 失败不改变旧链路结果，但必须写入诊断与 Trace event。

### Phase 3 验收

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.core.*" --tests "com.hirain.aiagent.runtime.*"
```

预期：首次调用和 Tool Calling 后续迭代都产生影子结果；无模型双调用、无 ChatMemory 双写；所有非允许差异为零。

---

## 6. Phase 4：完整预算、Memory 压缩能力与 Context Trace

**阶段目标：** 在影子链路中完成真实预算决策、压缩计划/写回能力的隔离验证和 Trace 层级改造；生产影子链路不得持久化压缩，避免改变旧真实链路读取的 ChatMemory。

### 6.1 本阶段工作边界

**本阶段允许修改：**

- Context 的预算策略、估算器、裁剪单元和报告。
- MemoryOrchestrator/MemoryCompressor 增加“压缩到目标预算”的计划和执行入口。
- SessionChatMemoryProvider 与 Store 的完整替换一致性，仅在 JVM/SQLite 集成测试中真实执行。
- TraceSession 的 parent-aware span 创建与 Context 专属 trace。

**本阶段禁止修改：**

- Context 不实现摘要 Prompt、调用压缩模型或直接操作 SQLite。
- Memory 不决定整个 ChatRequest 的预算，也不裁剪 Tool Schema、System Prompt 或 caller extra。
- 不对 SystemMessage、当前 UserMessage、Tool Request/Result 和 JSON schema 做 substring 截断。
- 不引入精确 tokenizer 依赖；生产预算值不从网络动态查询。
- 即使影子预算和 Trace 已就绪，真实 ChatRequest 仍不得提前切换。
- Phase 4 生产影子链路不得调用压缩模型、`replaceMessagesOrThrow()` 或失效 live cache；只生成 `MemoryCompactionPlan` 并在预算报告标记 `compressionRecommended=true`。

**阶段退出状态：** 影子结果已经可预算、可追踪并能生成确定的压缩计划；自动化测试证明压缩执行和一致写回可用；生产旧链路及其 ChatMemory 完全未被影子链路修改。

### 6.2 原代码具体修改点

| 原文件和位置 | 当前行为 | 本阶段如何修改 |
|---|---|---|
| `ContextBudgetManager.java` 的 `trim()`/`estimateTokens()` | 对字符串按 char limit 截断，以字符/4估算 | 保留旧 API 给兼容 build 使用；新增以 Contribution、ChatMessage、ToolSpecification 为输入的预算流程，旧 API 在 Phase 6 删除 |
| `MemoryCompressor.java` | 使用固定 `MAX_TOKENS=4000` 和固定保留规则，压缩后生成摘要消息 | 增加 target budget 参数和压缩计划结果；固定阈值只保留为旧入口默认值，新 Context 路径必须传目标预算 |
| `MemoryOrchestrator.onTurnComplete(...)` | turn 完成后按 Memory 自身阈值触发压缩/提取 | 保持 turn 完成职责；新增 `planSessionCompaction(...)` 和 `executeCompactionPlan(...)`。Phase 4 生产 Context 只调用 plan，Phase 5 才调用 execute |
| `SessionChatMemoryProvider.replaceMessages()` | `clear()` 后逐条 `add()`，Store 可看到中间状态 | 改为调用 `SessionMemoryStore.replaceMessagesOrThrow()` 一次提交；成功后失效旧缓存并要求调用方重取，失败时保留旧缓存；增加异常测试 |
| `TraceSession.startChildSpan()`、`startLlmSpan()`、`startToolSpan()` | Phase 1 已新增 parent-aware 重载，但旧调用仍默认 root | Phase 4 不再设计新 API，只把 Runtime、AgentTraceRecorder、ContextTraceRecorder 的真实调用迁移到显式 parent/scope，并删除不再需要的临时旧调用 |
| `TraceSpanNames.PROMPT_ASSEMBLY` | 实际记录整套消息装配 | 新增 CONTEXT_PREPARE、CONTEXT_ASSEMBLE；旧常量暂留兼容，Phase 6 删除 |
| `ContextTraceRecorder.java` | 只把 mode、provider 数、section 数等写 root attribute | 改为负责创建/结束 Context span、写 provider event、预算和压缩属性；不吞掉原始异常状态 |

### 6.3 预算组件之间的调用责任

- `ContextTokenEstimator` 只估算，不决定删除谁；它返回总量和按 source 的细分结果。
- `ContextBudgetManager` 根据 `ContextBudgetPolicy + ContextPriority` 生成确定性裁剪决策，不访问 MemoryStore。
- `ContextMessageAssembler` 按裁剪决策重新装配并调用 sequence validator，不自行修改优先级。
- `ContextOrchestrator` 发现主要压力来自 SessionMemory 时，计算 memory target 并调用 `planSessionCompaction()`；Phase 4 生产链路到此停止。
- Memory 集成测试调用 `executeCompactionPlan()`，验证压缩、写回、缓存失效和 MemoryCompactionResult；Context 不解析摘要正文来判断成功。
- Phase 5 启用真实 execute 后，Context 必须重新读取快照，不能继续使用压缩前缓存的 MessageContribution。

### 6.4 Trace 具体属性边界

- `context.prepare`：requestId 的脱敏/稳定关联值、provider count、required/optional outcome、static contribution count、duration。
- `context.assemble`：iteration、message count、tool count、selection mode、estimated tokens、budget utilization、dropped source keys、compression outcome。
- `agent.loop`：iteration count、termination reason、cancelled，不复制完整 Prompt。
- Provider 详情用 event：provider name、status、duration、error code；默认不记录 content。
- `prompt.render` 继续表达模板渲染，不能因为新增 Context span 而删除；`memory.compress` 继续表达压缩模型调用和写回。

### Task 4.1：实现模型级预算策略与低成本估算

**生产文件：** 创建 `ContextBudgetPolicy`、Estimator、Report；重构 `ContextBudgetManager`。

**实施步骤：**

- [ ] `ContextBudgetPolicy` 以 token 为逻辑单位，保存 `maxContextTokens`、`reservedOutputTokens`、`safetyMarginTokens`。
- [ ] TEXT qwen-turbo 的生产数值集中在 Service/Factory 装配处，通过构造参数注入，不从网络查询，不散落在 Provider。
- [ ] 实现保守字符/结构估算；分别估算消息正文、消息结构开销和 Tool JSON Schema。
- [ ] 缓存静态 System Prompt、未变化长期记忆和 Tool Schema 的估算结果；缓存键包含内容摘要，内容变化自动失效。
- [ ] 模型响应提供真实 usage 时只记录估算误差用于校准，不反向修改当前请求。
- [ ] 测试使用小预算注入，不依赖生产模型数值。

### Task 4.2：实现优先级裁剪和消息原子性

**预算顺序：** OPTIONAL Contribution -> 长期记忆完整条目 -> 低优先级工具候选 -> 一次 Memory 压缩 -> 最旧完整历史单元 -> CRITICAL 超限失败。

**实施步骤：**

- [ ] CRITICAL 内容包括唯一 SystemMessage、当前 UserMessage、当前 Tool Request/Result；永不 substring 截断。
- [ ] 长期记忆按完整条目删除；caller extra、普通时间等按完整 Contribution 删除。
- [ ] 明确 ToolGroup 的工具优先级为 HIGH；Demo 全量 fallback 是原子工具集合，不随机裁掉部分 schema。
- [ ] 使用 `ContextMessageSequenceValidator` 在裁剪前后各校验一次。
- [ ] 识别 `ConversationTurn` 与 `ToolExchange`，不得产生孤立 Tool Result。
- [ ] CRITICAL 内容或全量 fallback 原子工具集合本身超限时返回 `CONTEXT_BUDGET_EXCEEDED`，不得发送非法 ChatRequest。

### Task 4.3：实现压缩计划与可测试的一致写回能力

**生产文件：** 修改 `MemoryOrchestrator`、`MemoryCompressor`、`SessionChatMemoryProvider`，创建 `MemoryCompactionPlan` 和 `MemoryCompactionResult`。

**实施步骤：**

- [ ] 新增 `planSessionCompaction(sessionId, targetTokens, ...)`，返回不写 Store 的 `MemoryCompactionPlan`；Context 只提供目标，不实现摘要算法。
- [ ] 新增 `executeCompactionPlan(plan, traceParent)`，由 Memory 模块调用摘要模型并返回 MemoryCompactionResult；Phase 4 仅测试调用，生产 Context 不调用。
- [ ] MemoryCompressor 保留现有摘要职责，但按目标预算选择可压缩的完整历史单元，并保护最近对话和 ToolExchange 原子性。
- [ ] `SessionMemoryStore` 新增 `replaceMessagesOrThrow()`，不得吞掉 SQLite/序列化异常；`SessionChatMemoryProvider.replaceMessages()` 改为调用该入口，避免当前 clear + 逐条 add 的中间状态。
- [ ] Store 替换成功后移除旧 cache entry，并通过 MemoryCompactionResult/ContextAssemblyResult 通知 AgentLoop 重新取得 ChatMemory；失败时不得移除 cache。
- [ ] 写回失败时保持原有 live ChatMemory 和 SQLite 内容，不接受一边成功一边失败。
- [ ] Phase 4 生产影子链路只记录一次 compressionRecommended/plan 摘要，不调用执行入口；测试中执行后重新读取 MemorySnapshot，验证新快照和预算下降。
- [ ] 压缩失败可继续删除 OPTIONAL 内容；最终仍超限则明确失败。

### Task 4.4：建立真实 Trace 层级

**工作量与风险：** 高。该 Task 同时改变 span 生命周期、父子关系和名称，但不新建独立 LLM/Tool recorder。风险集中在 scope 未关闭、span 重复结束、异常路径丢失状态，以及旧测试只断言名称而未断言 parent。

**生产文件：**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSession.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceSpanNames.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/AgentTraceRecorder.java`
- Modify: `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`

**测试文件：**

- Modify: `app/src/test/java/com/hirain/aiagent/trace/TraceSessionParentChildTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/trace/AgentTraceRecorderTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/context/ContextTraceRecorderTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorTraceTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/trace/AIAgentServiceTraceWiringTest.java`

**目标层级：**

```text
agent.request
  agent.loop
    context.prepare
    context.assemble
      memory.read / memory.compress
    gen_ai.chat
    tool.execute
```

**实施步骤：**

- [ ] 使用 Phase 1 已建立的 parent-aware API；把 Runtime、ContextTraceRecorder、AgentTraceRecorder 的真实调用从默认 root 重载迁移到显式 parent/scope，不重复设计第二套 API。
- [ ] 实际启用 `agent.loop` span，并让每次 `context.assemble`、`gen_ai.chat` 和 `tool.execute` 挂在对应迭代下。
- [ ] `context.prepare` 记录 Provider 数量、成功/降级/失败结果、静态 Contribution 数量。
- [ ] `context.assemble` 记录迭代号、消息数、工具数、fallback、预算利用率、裁剪、压缩和最终结果。
- [ ] Provider 结果使用 span event，不为每个轻量 Provider 创建独立 span。
- [ ] 保留 `prompt.render`、memory read/extract/compress、gen_ai.chat、tool.execute、response.dispatch 等真实业务 span。
- [ ] 用 `context.assemble` 替代语义错误的 `prompt.assembly`；迁移期可双写名称但 Phase 6 必须删除旧 span。
- [ ] 内容采集复用现有 `TraceConfig.ContentCaptureMode`：生产只记录指标，debug redacted，FULL_DEBUG 仅用于受控调试。
- [ ] 层级测试同时断言 span name、spanId、parentSpanId、结束次数和错误状态；仅“找到 span 名称”不足以通过。

### Phase 4 验收

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.memory.*" --tests "com.hirain.aiagent.trace.*" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTraceTest"
```

预期：预算报告和压缩计划完整；测试环境证明一次压缩写回一致；生产影子测试证明压缩模型/Store/cache 均零调用；ToolExchange 不被拆散；Trace 父子关系符合目标树。

---

## 7. Phase 5：Context 独占切换

**阶段目标：** 将新装配结果切为 TEXT 主模型的唯一输入，旧链路停止写入，但迁移兼容类型暂留到下一阶段统一删除。

### 7.1 本阶段工作边界

**本阶段允许修改：**

- TEXT AgentLoop 的 ChatRequest 构造来源、构造器依赖和每轮调用顺序。
- TEXT AgentConfigFactory 的 PreProcessor 注册与固定 toolSubset 配置。
- AIAgentService 对 Context 全部生产依赖的装配。
- Runtime/ResultMapper 对 Context 失败码和取消结果的映射。
- 启用 Phase 4 已验证的 `executeCompactionPlan()` 生产调用和压缩后 ChatMemory 重载。

**本阶段禁止修改：**

- 不改 scene/VL AgentConfig 与模型调用路径。
- 不把模型调用、ToolExecutor、SafetyGuard、Terminator、ResultCollector 移入 Context。
- 不把 `chatMemory.add()` 交给 Context；Context 只能读取快照。
- 不删除旧类型和影子设施，先保留一个阶段用于切换回归；但旧链路不得再参与真实 ChatRequest。
- 不新增 AIDL 字段或改变 Launcher 调用协议。

**阶段退出状态：** TEXT 真实模型输入完全由 Context 生成；旧路径仍可编译但已无生产写入权；完整回归通过后才能进入清理。

### 7.2 原代码具体修改点

| 原文件和位置 | 当前行为 | 本阶段如何修改 |
|---|---|---|
| `AgentLoopOrchestrator` 构造器 | 接收 PromptManager、MemoryOrchestrator 和构造期 ToolSpecification，形成固定 `effectiveToolSpecs` | 增加 ContextAssemblyGateway；TEXT 构造不再需要 PromptManager 做消息装配，也不接收固定 specs；暂时保留兼容构造器给非 TEXT/旧测试 |
| `AgentLoopOrchestrator.execute(String, Map...)` 开始处 | 选择 ChatMemory、创建当前 UserMessage、运行 PreProcessor | 改为强类型 execute；当前 UserMessage 的创建结果来自 prepared Context 契约，但写入正确 Session ChatMemory 仍由 AgentLoop 完成且只执行一次 |
| AgentLoop 的 `buildSystemPromptMessage()` | PromptManager 渲染后调用 `memoryOrchestrator.prepareSystemPrompt()` 拼长期记忆 | TEXT 路径停止调用；System Prompt 和长期记忆分别由 Prompt/Memory Provider 提供 |
| AgentLoop 的 `allMessages` 与 `ChatRequest.builder()` | 手工合并 system、transient、chatMemory，并绑定 `effectiveToolSpecs` | 直接使用 assemblyResult.messages() 和 toolSpecifications()；构造前只检查 result success/cancel，不修改内容 |
| `AgentConfigFactory.createTextPersona()` | 注册 ContextExtra、VehicleStatus、Time PreProcessor，`.toolSubset(null)` 表示固定全量 | 移除这三个 TEXT 消息型 PreProcessor；toolSubset 不再决定 TEXT 真实规格，保留 safety/post/terminator 等配置 |
| `AIAgentService.kt` 378-405 附近 | Orchestrator 接收 `toolRegistry.toolSpecifications`；ContextBuildInput 设置 HYBRID；Runtime lambda 接收 Map | 改为装配 ContextPolicy/Assembler/Budget/Gateway 并注入 TEXT Orchestrator；移除 HYBRID 参数和固定 specs；Runtime lambda 改用强类型 frame/result |
| `AgentRuntime.execute()` | Context build 后经 `AgentExecutor.execute(session, frame)` 适配到 Map | 直接传 ContextPrepareResult；Context required failure 在进入 AgentLoop 前返回 Runtime failure，optional fallback 继续执行 |

### 7.3 AgentLoop 切换后的单轮顺序

1. 选择/取得当前 session ChatMemory。
2. 首轮将当前 UserMessage 写入一次；后续工具迭代不得再次写入。
3. 创建 `ContextAssemblyRequest`，读取当前循环 iteration、ChatMemory 和取消状态。
4. Context 生成并验证 `ContextAssemblyResult`。
5. AgentLoop 原样把 messages/specs 放入 ChatRequest。
6. ModelCaller 返回 AiMessage 后，AgentLoop 写入 ChatMemory。
7. SafetyGuard 审查工具调用，ToolExecutor 执行允许调用，AgentLoop 写入所有 ToolResult。
8. Terminator 决定结束或下一轮；下一轮返回步骤 3，动态 Provider 重新读取。

该顺序需要通过测试证明当前用户消息不重复、工具结果不丢失、取消后不产生迟到写入。

### Task 5.1：AgentLoop 只消费 ContextAssemblyResult

**生产文件：** 修改 `AgentLoopOrchestrator`、`AgentConfig`、`AgentConfigFactory`。

**实施步骤：**

- [ ] 将 AgentLoop 每轮流程改为：读取循环状态 -> 调用 ContextAssemblyGateway -> 构造 ChatRequest -> 调模型。
- [ ] ChatRequest 的 messages 和 toolSpecifications 原样来自 `ContextAssemblyResult`；AgentLoop 不追加、不重排、不裁剪。
- [ ] 删除真实路径中的 `buildSystemPromptMessage()` 调用、`transientMessages` 拼接和 `allMessages` 局部组装。
- [ ] 移除构造期固定 `effectiveToolSpecs` 对 TEXT 的控制；工具执行仍由 ToolRegistry/ToolExecutor 负责。
- [ ] 当前 UserMessage 仍只在每个 turn 写入一次 ChatMemory；Context 读取而不重复写入。
- [ ] AiMessage、ToolExecutionResultMessage 和最终结果写入职责继续归 AgentLoop/Memory，不转移给 Assembler。
- [ ] Context 必需错误、预算错误和取消状态映射到现有 AgentResult/RuntimeResult 失败语义，不发送模型请求。
- [ ] 在 Context 独占路径启用 `executeCompactionPlan()`：同一请求最多一次；成功后重新取得 ChatMemory 并重新 assemble，失败后按预算策略降级或失败。

**当前 UserMessage 的失败/取消语义：**

- AgentLoop 在 iteration 0 前写入 currentUserMessage 后，该写入是单向提交，不参与模型调用事务。
- prepare 在写入前取消时不写消息；写入后 assemble、预算、模型调用失败或取消时不回滚该 UserMessage。
- 这样保留用户已经表达的真实意图，下一次请求可以看到该消息；可能出现“有 UserMessage、没有 AiMessage”的合法历史状态。
- 不允许因此产生孤立 ToolResult；ToolResult 仍必须与已经写入的 Ai tool request 成组。
- 增加测试：cancel_before_user_write 不新增消息、cancel_after_user_write 保留一条消息、assemble_failure 保留一条消息、下一请求读取该消息、iteration 1 不重复写入。

### Task 5.2：移除 TEXT 消息型 PreProcessor 注册

**生产文件：** 修改 `AgentConfigFactory.createTextPersona(...)` 及相关工厂测试。

**实施步骤：**

- [ ] TEXT 配置不再注册 `ContextExtraPreProcessor`。
- [ ] TEXT 配置不再注册 `VehicleStatusPreProcessor` 和 `TimeContextPreProcessor`。
- [ ] 检查 MemoryPreProcessor 是否仍被 TEXT 使用；若其职责已经由 Context/MemoryOrchestrator 覆盖，移除 TEXT 注册。
- [ ] 安全审查、模型调用、工具执行、后处理、终止器和结果收集器保持原职责与顺序。
- [ ] 增加防重复测试：车辆、时间、长期记忆和当前用户输入在最终 ChatRequest 中各出现一次。

### Task 5.3：完成 Service/Runtime 生产装配

**生产文件：** 修改 `AIAgentService.kt`、`AgentRuntime.java`、`AgentExecutor.java`。

**实施步骤：**

- [ ] Service 装配真实 ContextPolicy、Provider 列表、BudgetPolicy、Estimator、Assembler、MemoryOrchestrator 和 ToolRegistry。
- [ ] 删除生产装配中的 `ContextMode.HYBRID_EXTRA_CONTEXT` 设置；迁移期类型可暂留编译，不能再决定真实路径。
- [ ] AgentRuntime 只传递强类型 `ContextPrepareResult/ContextFrame`，不重新渲染 extra context。
- [ ] 保证 cancel 在 prepare、assemble、compression 前后和模型调用前均可终止；取消后不写 ChatMemory、不执行工具。
- [ ] 保持 AIDL、RequestSession 和响应映射外部协议不变。
- [ ] `AgentRuntime.execute()` 在创建 agent.loop span 和调用 ContextPreparer 之前校验 TEXT `session.userInput()` 非 null 且 `trim()` 后非空；空值或纯空白直接返回 `INVALID_INPUT`，Context Provider、AgentExecutor、ChatMemory 均零调用。
- [ ] `AgentResult.ErrorType` 增加 `INVALID_INPUT`，RuntimeResponseMapper 映射为现有失败响应格式；不抛异常穿过 AIDL。
- [ ] 本轮只在 Runtime 建立权威校验，不在 AIAgentService 复制第二套分支，避免 AIDL 直接响应与 RuntimeResponseMapper 出现两种错误格式；Service 继续把请求交给 Runtime。

### Task 5.4：端到端行为回归

- [ ] 普通聊天：空工具集合或明确 CHAT_ONLY，消息顺序正确。
- [ ] 明确车控：只暴露命中的工具组，车辆状态为 HIGH。
- [ ] 模糊车控：`allToolsFallback=true`，暴露全部已启用工具并记录 HIGH 风险。
- [ ] 多轮工具调用：第二轮重新读取车辆状态，工具消息完整。
- [ ] session 切换：不携带上一个 session 的短期对话。
- [ ] user 切换：保留当前 session 对话，长期记忆切换到新 user。
- [ ] persona 切换：System Prompt 切换，不污染其他 session/user 记忆。
- [ ] 预算超限：按策略裁剪或压缩，不能发送非法请求。
- [ ] Provider 失败和取消：required 失败中止，optional 记录降级。
- [ ] 空 TEXT：返回 INVALID_INPUT，Context/模型/Memory 都没有调用。

### Phase 5 验收

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

预期：完整单元测试与 Debug 构建通过；TEXT 真实 ChatRequest 仅来自 Context；非 TEXT 链路行为不变。

---

## 8. Phase 6：删除迁移债务与最终验收

**阶段目标：** 删除全部模式、兼容和影子代码，使最终架构只有一条可运行的 TEXT Context 链路。

### 8.1 本阶段工作边界

**本阶段允许修改：**

- 删除已经零调用的模式、Section、BuildResult、Map adapter、旧构造器、影子设施和旧测试。
- 更新 Context、AgentLoop、ToolGroup、Memory、Trace 文档和最终测试报告。
- 收紧构造器和方法可见性，防止后续重新绕过 Context。

**本阶段禁止修改：**

- 不趁清理阶段重构 ToolRegistry、Memory 算法、AIDL 或非 TEXT 链路。
- 不删除仍被 scene/VL 使用的 PreProcessor、Prompt 或工具代码。
- 不保留“暂时未使用但以后可能用”的 HYBRID/OBSERVE/Map 兼容入口。
- 不把失败测试直接删除来获得绿色构建；旧行为测试必须由对应最终行为测试替代。

**阶段退出状态：** 仓库中不存在可重新启用旧 TEXT 输入链路的模式或 adapter；Context 独占权由类型和构造依赖共同保证。

### 8.2 删除顺序和引用判定

删除必须按以下顺序执行，避免一次性删除导致无法判断真实调用者：

1. 先删除 Service/Factory 对旧构造器和 mode 的引用。
2. 再删除 AgentRuntime/AgentExecutor 的 Map adapter 与 `ContextFrame.toOrchestratorContext()`。
3. 删除 AgentLoop 的旧 System/transient/fixed tools 方法和字段。
4. 删除 TEXT PreProcessor 注册后，全仓确认具体 PreProcessor 是否仍有非 TEXT 调用者。
5. 删除旧 Context Section/Build 类型和 ContextMode。
6. 删除影子比较代码和兼容测试。
7. 最后删除 `prompt.assembly` 常量及 recorder，避免 Trace 调用点遗漏。

每一步都应执行编译或目标测试；不得把所有删除集中到一次无法定位问题的大改动中。

### 8.3 最终 API 形态要求

- `AgentRuntime` 对 TEXT 只依赖 `ContextPreparer` 和强类型 AgentExecutor，不暴露 Context Map key。
- `AgentLoopOrchestrator` 对 TEXT 构造器必须要求 `ContextAssemblyGateway`，且没有 PromptManager/固定 ToolSpecification 输入参数。
- `ContextFrame` 只保存请求级事实和 Contribution，不保存 mode、rendered extra 或重复业务摘要字段。
- `ContextOrchestrator` 只公开 prepare/assemble 所需入口，不再公开 `build()` 旧语义。
- `ContextProviderResult` 只返回 Contribution/outcome，不再返回 ContextSection。
- `ContextBudgetManager` 只处理结构化输入和预算报告，不保留字符串 char-limit 主路径。
- Trace 中只有 `context.prepare/context.assemble` 表达 Context 工作，不再用 `prompt.assembly` 代替。

### Task 6.1：删除旧模式和兼容数据结构

- [ ] 删除 `ContextMode.java` 及所有 mode 字段、判断和测试。
- [ ] 删除 OBSERVE/HYBRID/FULL 分支，不保留单值枚举或配置开关。
- [ ] 删除 `context_rendered_extra`、`context_mode`、`caller_extra_context` 等旧 Map 兼容键。
- [ ] 删除 `ContextFrame.toOrchestratorContext()`；确认 AgentExecutor 已只有 `execute(RequestSession, ContextPrepareResult)` 强类型入口。
- [ ] 删除已被 Contribution/AssemblyResult 替代的 Section、BuildResult、DebugInfo 类型；每次删除前先做全仓引用搜索。
- [ ] 删除 `ContextExtraPreProcessor`；其他 PreProcessor 仅在无任何非 TEXT 调用者时删除。

### Task 6.2：删除 AgentLoop 旧输入所有权

- [ ] 删除 `AgentLoopOrchestrator.buildSystemPromptMessage()`。
- [ ] 删除 AgentLoop 固定 `effectiveToolSpecs` 字段和相关 constructor 参数。
- [ ] 删除旧 transient/allMessages 组装辅助方法。
- [ ] 删除旧 `prompt.assembly` recorder 和 span name。
- [ ] 删除仅验证旧消息拼接的测试，改为验证 ContextAssemblyResult 到 ChatRequest 的直通契约。

### Task 6.3：删除影子设施并更新文档

- [ ] 删除 ContextShadowComparator、Recorder、迁移开关和仅迁移期使用的允许差异表代码。
- [ ] 保留最终装配诊断和 Context Trace，不删除生产可观测性。
- [ ] 更新 `docs/overview/context-module-overview.md`，描述最终职责、调用链、Provider 和完成度。
- [ ] 更新 Context 架构图、Trace 层级和 ToolGroup 动态绑定现状。
- [ ] 在 `docs/testresult/` 新增本轮最终验证报告，记录命令、结果、测试数量和未覆盖设备项。

### Task 6.4：静态所有权审计

使用搜索确认不存在旧链路。PowerShell 环境若 `rg` 不可用，使用 `Get-ChildItem | Select-String` 执行等价检查。

检查项：

- [ ] `ContextMode`、`HYBRID_EXTRA_CONTEXT`、`OBSERVE_ONLY`、`FULL_CONTEXT` 引用为零。
- [ ] `context_rendered_extra`、`context_mode` 引用为零。
- [ ] TEXT Factory 不包含 Context/Vehicle/Time 消息型 PreProcessor。
- [ ] AgentLoop 不调用 PromptManager、不构造 SystemMessage、不合并 transient messages。
- [ ] AgentLoop 的 ChatRequest 消息和工具只取自 `ContextAssemblyResult`。
- [ ] `prompt.assembly` span 引用为零，`context.prepare/context.assemble/agent.loop` 均有测试。
- [ ] Tool Calling result 不存在孤立消息。
- [ ] 非 TEXT 路径相关类没有被误删。

### Phase 6 最终验证

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

预期：全部命令成功；无旧模式和兼容引用；最终文档与代码一致。

设备或集成环境可用时，再执行以下人工验证；设备不可用不得伪报完成，应在 testresult 中明确记录未执行：

- 连续普通对话与 session 切换。
- 对话中切换用户，验证短期上下文保留且长期记忆切换。
- 明确车控和模糊车控，核对工具集合及 Trace fallback 属性。
- Tool Calling 两轮以上，核对车辆状态刷新和 Trace 父子关系。
- 请求执行中取消，确认没有迟到工具执行和记忆写入。

---

## 9. 测试矩阵与阶段门禁

| 能力 | 关键测试 | 最晚通过阶段 |
|---|---|---|
| Contribution 强类型与不可变性 | Context contract tests | Phase 1 |
| 唯一 System 与固定消息顺序 | ContextMessageAssemblerTest | Phase 1 |
| ToolExchange 原子关系 | ContextMessageSequenceValidatorTest | Phase 1 |
| Provider 真实数据 | Provider integration tests | Phase 2 |
| 用户/会话记忆隔离 | Memory + Context integration tests | Phase 2 |
| 明确工具组动态规格 | Tool Provider tests | Phase 2 |
| 全量 fallback 工具 | Tool Provider fallback tests | Phase 2 |
| 无双模型调用/双记忆写入 | AgentLoopContextShadowTest | Phase 3 |
| 工具后车辆状态刷新 | multi-iteration test | Phase 3 |
| 完整预算与原子裁剪 | ContextBudgetManagerTest | Phase 4 |
| 一次压缩与一致写回 | Memory compaction tests | Phase 4 |
| Trace 父子层级 | TraceSession/AgentLoop trace tests | Phase 4 |
| Context 独占 ChatRequest | end-to-end capturing model tests | Phase 5 |
| 取消与失败映射 | Runtime cancellation/failure tests | Phase 5 |
| 旧引用清零与全回归 | static audit + full Gradle commands | Phase 6 |

阶段门禁规则：

- 任一阶段失败时，不进入下一阶段的真实路径切换。
- Phase 3 的非允许差异未清零时，不进入 Phase 5。
- Phase 4 的 Memory 写回一致性和 ToolExchange 原子性未通过时，不进入 Phase 5。
- Phase 5 的真实模型输入切换完成前，不删除旧路径；切换通过后必须立即进入 Phase 6 清理，不能长期双轨运行。
- Demo 全量 fallback 可以完成本轮验收，但最终生产发布前必须另行完成 ToolExecutor 统一安全授权模块。

---

## 10. 主要风险与控制措施

| 风险 | 后果 | 控制措施 |
|---|---|---|
| 当前工作区已有 Memory/ToolGroup 改动 | 计划执行时误覆盖用户代码 | 每阶段先读当前文件与 git diff，只做范围内增量修改 |
| 当前 UserMessage 被重复写入 | 模型看到重复问题、记忆污染 | AgentLoop 单写，Context 只读；增加唯一性测试 |
| UserMessage 写入后请求取消或 assemble 失败 | 历史中出现无 Ai 回复的 UserMessage | 明确为单向提交、不回滚；测试下一请求可读取且不重复写入 |
| 长期记忆仍拼进 System Prompt | 信任边界不清、重复注入 | 新 Provider 读取结构化快照，禁止调用 prepareSystemPrompt |
| Tool fallback 暴露 HIGH 风险工具 | Demo 中产生危险工具请求 | 明确标记 Demo、Trace 高风险；生产化前增加统一执行安全门 |
| 工具 schema 预算过大 | 全量 fallback 无法发送 | 原子保留或明确预算失败，不随机裁剪 schema |
| ChatMemory 压缩写回部分成功 | live cache 与 SQLite 分裂 | 单事务替换或失败回滚；压缩后重新读取验证 |
| 工具消息被裁剪破坏 | LangChain4j 请求非法 | ToolExchange 原子单元及前后 validator |
| Trace 仍然扁平 | 无法定位 Context 耗时和失败 | parent-aware span API + 层级断言测试 |
| Phase 4 影子链路执行真实压缩 | 旧真实链路 ChatMemory 被影子逻辑改变 | Phase 4 生产只生成 MemoryCompactionPlan；Phase 5 独占后才启用 execute |
| 空 TEXT 到达 UserInput Provider | 缺少 currentUserMessage、错误映射混乱 | Runtime 在 Context 前返回 INVALID_INPUT，Provider/Memory/模型零调用 |
| 影子链路长期保留 | 双重复杂度、维护成本上升 | Phase 6 将删除影子代码设为硬验收项 |
| 误删非 TEXT PreProcessor | scene/VL 回归 | 删除前全仓引用审计和完整测试 |

---

## 11. 计划自检

- [x] 范围限定为 TEXT 主 AgentLoop 及每次 Tool Calling 迭代。
- [x] Context 接管模型输入控制，但不吞并 Prompt、Memory、Tool、Vehicle 的底层实现。
- [x] 保留 LangChain4j 低层原语和自研 AgentLoop，未强制迁移 AiServices。
- [x] 包含强类型 Contribution、真实 Provider、纯 Assembler、完整预算和压缩边界。
- [x] 包含请求级/迭代级生命周期，覆盖工具执行后动态车辆状态。
- [x] 包含 session 短期记忆隔离和 user 长期记忆切换目标。
- [x] 包含 Demo 全量工具 fallback 的已确认决策及生产化风险说明。
- [x] 包含 Context Trace 新 span、旧 prompt.assembly 收敛和父子层级修复。
- [x] 采用六个阶段，每阶段均有测试门禁，未过度拆分章节。
- [x] 明确最终删除模式、兼容键、旧 PreProcessor、旧 AgentLoop 装配和影子代码。
- [x] 已固定 ContextPreparer、ContextAssemblyGateway、AgentExecutor 和 TEXT AgentLoop 的接口语义与迁移顺序。
- [x] 已固定当前 UserMessage 的唯一生成/写入流程，避免空白执行者重复注入。
- [x] 已固定 Provider 顺序、required 规则、Context Data 格式和工具解析算法。
- [x] 已固定 Memory Store 抛错、缓存失效、ChatMemory 重载协议，不留原子写回实现分支。
- [x] 已固定 qwen-turbo Demo 预算数值和首版估算公式，不要求执行者自行选择。
- [x] 已固定 agent.loop span 的创建位置和 prepare/assemble/LLM/tool 父子关系。
- [x] 已把 Trace parent-aware API 提前到 Phase 1，并列出真实 recorder 与层级测试文件。
- [x] 已列出 AgentExecutor 签名变更影响的 Service 和 runtime 测试调用点。
- [x] 已区分 RuntimeCancelChecker 与 ContextCancelChecker，二者共享同一取消事实。
- [x] 已具体化 8 个影子基线场景和 scenarioId 允许差异规则。
- [x] 已明确 UserMessage 写入后失败/取消不回滚，并要求覆盖该历史状态。
- [x] 已增加 Runtime 空 TEXT 权威校验和 INVALID_INPUT 映射。
- [x] 已禁止 Phase 4 生产影子持久化压缩，真实执行延后到 Phase 5。
- [x] 文档中不存在需要执行者自行选择的未决占位符；代码漂移只能做语义等价调整并记录。
- [x] 未要求本轮修改 AIDL、非 TEXT 链路、依赖版本或本地配置文件。
- [x] 计划只描述修改内容、方法、边界和验收，不包含完整实现代码。
