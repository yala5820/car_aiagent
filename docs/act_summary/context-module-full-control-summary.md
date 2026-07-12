# Context 全权控制改造 — 项目阶段总结

**生成日期：** 2026-07-11
**范围：** Phase 1 ~ Phase 6
**提交至：** 项目总工程师

---

## 一、工作目标

将 TEXT 请求的模型输入控制权从 `AgentLoopOrchestrator` 和 TEXT `PreProcessor` 链迁移到 `ContextOrchestrator + ContextMessageAssembler`。改造完成后，任何进入 TEXT 主模型 `ChatRequest` 的 `ChatMessage` 和 `ToolSpecification` 都必须由 Context 模块统一选择、排序、预算和输出，AgentLoop 不再拥有 Prompt 拼接、临时上下文注入、历史消息排序或工具可见性决策。

Context 只接管模型输入策略和装配，不吸收 Memory、Prompt、Tool、Vehicle、Trace 等能力模块的底层实现。

---

## 二、最终架构

### 2.1 调用链

```
AgentRequest
  → AgentRuntime.startSession()
      → SessionIdResolver
      → IntentRouter
      → ToolGroupSelector
      → RequestSession
  → AgentRuntime.execute()
      → [agent.loop span] ContextOrchestrator.prepare()
          → request-static Providers → ContextFrame
      → AgentExecutor.execute(session, prepareResult)
          → AgentLoopOrchestrator.execute(session, prepareResult)
              → 写入 currentUserMessage 到 ChatMemory（仅 iteration 0）
              → ContextOrchestrator.assemble(request)
                  → iteration-dynamic Providers
                  → [context.assemble span] ContextMessageAssembler
                  → ContextAssemblyResult (messages + toolSpecifications)
              → ChatRequest(messages=result.messages(), toolSpecifications=result.toolSpecifications())
              → ModelCaller → ToolExecutor → 下一轮迭代
```

### 2.2 三层责任模型

```
能力模块层          Memory / Prompt / Tool / Vehicle / Trace
（拥有数据算法）    底层实现不变，通过窄接口暴露只读数据

Context 控制层       ContextOrchestrator + ContextProvider + ContextMessageAssembler
（拥有输入策略）     决定模型可见性、生命周期、优先级、顺序、预算

AgentLoop 执行层     AgentLoopOrchestrator
（拥有循环控制）     负责循环状态、模型调用、工具执行、ChatMemory 写入
```

### 2.3 TEXT AgentLoop 最终签名

```java
// TEXT 专用构造器（不接收 PromptManager 或固定 ToolSpecification）
public AgentLoopOrchestrator(AgentConfig config, Context context,
    MemoryOrchestrator memoryOrchestrator, List<ToolSpecification> allToolSpecs,
    ContextAssemblyGateway contextAssemblyGateway)

// TEXT 专用执行入口（不使用 buildSystemPromptMessage / transientMessages / allMessages）
public AgentResult execute(RequestSession session, ContextPrepareResult prepareResult)
```

---

## 三、分阶段工作内容

### Phase 1：基线测试与强类型骨架

| 工作项 | 具体内容 |
|--------|---------|
| CapturingModelCaller | 可复用的 ChatRequest 捕获工具 |
| LegacyTextInputCharacterizationTest | 8 个旧链路场景的固化测试 |
| 基线矩阵文档 | `docs/testresult/context-shadow-baseline-matrix.md` |
| Contribution 体系 | ContextContribution 接口 + Text/Message/Tool 三种具体贡献类型 |
| Visibility/Trust/Priority/Lifecycle | 4 个枚举定义贡献元数据 |
| ContextProviderOutcome / ContextErrorCode | Provider 执行结果状态 + 领域错误码 |
| ContextMessageAssembler | 纯函数式消息装配器 + ContextMessageSequenceValidator |
| ContextAssemblyRequest / ContextAssemblyResult | 装配输入/输出契约 |
| Trace parent-aware API | TraceSession 新增 startChildSpan(name, parent) 重载 |
| TraceSessionParentChildTest | 三级 Trace 层级断言测试 |

**涉改文件：** 30+ 新建/修改（Context 数据模型 + Trace API + Contribution 类型 + Assembler）

---

### Phase 2：Provider 真实化与能力模块读接口

| 工作项 | 具体内容 |
|--------|---------|
| PromptContextProvider | 调用 PromptManager.render() 输出真实 System Prompt（TRUSTED_SYSTEM, CRITICAL） |
| UserInputContextProvider | 使用 SpeakerMessageFormatter 生成唯一当前 UserMessage（MessageContextContribution） |
| RuntimeContextProvider | 改为 TRACE_ONLY（运行 ID 不进入模型） |
| IntentContextProvider | 改为 POLICY_ONLY（意图仅供策略决策） |
| PersonaContextProvider | 改为 POLICY_ONLY（人格数据由 Prompt Provider 渲染） |
| ToolGroupContextProvider | 同时输出旧 Section + ToolContextContribution（真实 ToolSpecification） |
| LongTermMemoryContextProvider | 新增，读取 MemoryOrchestrator.longTermMemorySnapshot() |
| SessionMemoryContextProvider | 新增，读取 MemoryOrchestrator.sessionMemorySnapshot() |
| CallerExtraContextProvider | 新增，读取 caller extra，标记 UNTRUSTED_DATA |
| VehicleStateContextProvider | 改为 ITERATION_DYNAMIC，MODEL_VISIBLE |
| TimeContextProvider | 改为 ITERATION_DYNAMIC，MODEL_VISIBLE |
| ContextOrchestrator | 拆分为 requestStaticProviders + iterationDynamicProviders；新增 prepare() + assemble() |
| ContextBuildInput | 新增 ToolRegistry、ContextPolicy、BudgetPolicy、TokenEstimator 字段 |
| ToolRegistry | 新增 toolSpecificationsByNames() + enabledToolSpecifications() |
| MemoryOrchestrator | 新增 longTermMemorySnapshot() + sessionMemorySnapshot() |
| LongTermMemorySnapshot | 新建不可变快照类型 |
| MemorySnapshot | 修复 sessionId 前缀问题，新增 rawSessionId 构造标记 |
| ContextPreparer / ContextAssemblyGateway | 窄接口文件 |
| ContextPrepareResult | 新建 prepare 结果类型 |
| ContextDebugInfo / VehicleStatusProvider | Phase 1 已超前创建的依赖补全 |

---

### Phase 3：影子装配与动态工具差异验证

| 工作项 | 具体内容 |
|--------|---------|
| AgentExecutor SAM 变更 | `execute(String, Map)` → `execute(RequestSession, ContextPrepareResult)` |
| AgentRuntime.execute() | 改为 ContextPreparer.prepare() → RuntimeCancelChecker → AgentExecutor |
| AIAgentService lambda | 迁移桥（Phase 3 从 ContextPrepareResult 还原旧 Map） |
| 所有 runtime 测试 lambda | 20+ 个 lambda 全部更新 |
| AgentLoopOrchestrator 影子装配 | 新增 ContextAssemblyGateway 字段，每轮迭代前执行 shadow assemble |
| ContextOrchestrator.prepare() | 真正执行 request-static Provider 链 |
| ContextShadowComparator | 消息类型/顺序/数量规范化比较，支持 8 个 scenarioId |
| ContextShadowRecorder | 影子比较结果记录，写入 Trace event |
| ContextShadowComparison | MATCH/EXPECTED_DIFFERENCE/BLOCKING_DIFFERENCE/NEW_ASSEMBLY_FAILED |
| classifyDifference Bug 修复 | 修复允许差异逻辑（必须明确匹配 reason 才放行） |
| textOrchestrator 注入 gateway | AIAIAgentService 将 contextOrchestrator 作为 Gateway 注入 |
| AgentLoop 影子比较 | 影子结果使用 Comparator 比较并记录 |

---

### Phase 4：完整预算、Memory 压缩与 Trace 层级

| 工作项 | 具体内容 |
|--------|---------|
| ContextTokenEstimator | 接口定义 |
| HeuristicContextTokenEstimator | 保守字符/结构估算（×1.15 安全系数） |
| ModelContextWindowProfiles | `qwenTurboDemo()` → ContextBudgetPolicy(32768, 2048, 1024) |
| ContextBudgetManager 结构化 API | 新增 generateBudgetReport() + ConversationTurn 识别 |
| MemoryCompactionPlan | 不可变压缩计划（不调用模型、不写 Store） |
| MemoryCompactionResult | 执行结果（tokensBefore/tokensAfter/reloadRequired） |
| MemoryPersistenceException | SQLite 失败时抛出，不得吞异常 |
| MemoryOrchestrator | 新增 planSessionCompaction() + executeCompactionPlan() |
| MemoryCompressor.planCompact() | 不调用 LLM 的 plan 版本 |
| SessionMemoryStore.replaceMessagesOrThrow() | 原子 INSERT OR REPLACE，失败抛异常 |
| SessionChatMemoryProvider.replaceMessages() | 改为调用 replaceMessagesOrThrow() + 失效 cache |
| TraceSpanNames | 新增 CONTEXT_PREPARE / CONTEXT_ASSEMBLE |
| AgentRuntime | 创建 agent.loop span |
| ContextTraceRecorder | 新增 startPrepareSpan() / startAssembleSpan() |
| ContextOrchestrator.prepare() | 创建 context.prepare span |
| ContextOrchestrator.assemble() | 创建 context.assemble span |

---

### Phase 5：Context 独占切换

| 工作项 | 具体内容 |
|--------|---------|
| AgentLoopOrchestrator 新 execute() | 新增 `execute(RequestSession, ContextPrepareResult)` — Context 装配结果直接驱动 ChatRequest |
| ContextAssemblyRequest 完善 | 携带真实 ContextFrame + RequestSession + ContextBudgetPolicy |
| ContextOrchestrator.assemble() 完整实现 | 传递真实 session，调用 ContextMessageAssembler 生成消息列表 |
| AIAgentService lambda | 移除迁移桥，直接调用 `textOrchestrator.execute(session, prepareResult)` |
| AgentResult.ErrorType | 新增 INVALID_INPUT / CONTEXT_BUILD_FAILED / CONTEXT_BUDGET_EXCEEDED |
| RuntimeResponseMapper | 新增对应 3 条分支映射 |
| TEXT PreProcessor 移除 | ContextExtraPreProcessor / VehicleStatusPreProcessor / TimeContextPreProcessor 从 createTextPersona() 移除 |
| ContextAssemblyRequest.session | 新增 RequestSession 字段供迭代级 Provider 使用 |

---

### Phase 6：删除迁移债务

| 工作项 | 具体内容 |
|--------|---------|
| ContextShadowComparison/Comparator/Recorder 删除 | 3 个文件 + 测试文件 + AgentLoop 引用 |
| ContextFrame.toOrchestratorContext() 删除 | 方法本身 + Map 兼容键（context_rendered_extra / context_mode / caller_extra_context） |
| ContextMode 枚举删除 | 枚举文件 + 21 个文件引用（7 生产 + 14 测试） |
| ContextExtraPreProcessor 删除 | 文件 + AgentConfigFactory import + 2 个测试文件 |
| prompt.assembly / PROMPT_ASSEMBLY 删除 | TraceSpanNames 常量 + AgentTraceRecorder 方法 |
| buildSystemPromptMessage() 删除 | AgentLoopOrchestrator 方法 |
| ContextBuildInput.mode() 删除 | Builder + 字段 |

---

## 四、Context 模块最终实现

### 4.1 模块结构

```
context/
├── ContextOrchestrator.java          # 核心执行器，实现 ContextPreparer + ContextAssemblyGateway
├── ContextPreparer.java              # 请求级准备窄接口
├── ContextAssemblyGateway.java       # 装配窄接口
├── ContextFrame.java                 # 不可变上下文快照
├── ContextFrameBuilder.java          # 构造器
├── ContextBuildInput.java            # 依赖容器
├── ContextProvider.java              # Provider 接口（name/lifecycle/required/provide）
├── ContextProviderResult.java        # 执行结果（Contribution + outcome）
├── ContextContribution.java          # 贡献基础接口
├── TextContextContribution.java      # 文本贡献（SYSTEM / CONTEXT_DATA）
├── MessageContextContribution.java   # 消息贡献（CURRENT_USER / SESSION_MEMORY）
├── ToolContextContribution.java      # 工具贡献（SELECTED / ALL_FALLBACK / NONE）
├── ContextVisibility.java            # MODEL_VISIBLE / POLICY_ONLY / TRACE_ONLY
├── ContextTrustLevel.java            # TRUSTED_SYSTEM / TRUSTED_DATA / UNTRUSTED_DATA
├── ContextLifecycle.java             # REQUEST_STATIC / ITERATION_DYNAMIC
├── ContextPriority.java              # CRITICAL / HIGH / NORMAL / OPTIONAL / TRACE_ONLY
├── ContextProviderStatus.java        # SUCCESS / FALLBACK / FAILED / CANCELLED
├── ContextProviderOutcome.java       # Provider 执行结果
├── ContextErrorCode.java             # 领域错误码
├── ContextPrepareResult.java         # prepare 结果（含 ContextFrame + currentUserMessage）
├── ContextAssemblyRequest.java       # 装配请求（frame + iteration + sessionMessages + session）
├── ContextAssemblyResult.java        # 装配结果（messages + toolSpecifications + budgetReport）
├── ContextAssemblyDebugInfo.java     # 装配诊断
├── ContextMessageAssembler.java      # 纯函数消息装配器
├── ContextMessageSequenceValidator.java # 消息序列校验
├── ContextTokenEstimator.java        # Token 估算接口
├── HeuristicContextTokenEstimator.java # 保守估算实现
├── ContextBudgetManager.java         # 预算管理（旧字符级 + 新结构化）
├── ContextBudgetPolicy.java          # 预算策略（maxContextTokens / reservedOutputTokens / safetyMargin）
├── ContextBudgetReport.java          # 预算报告
├── ContextCancelChecker.java         # 取消检查窄接口
├── ContextTraceRecorder.java         # Trace 记录（写入 root / context.prepare / context.assemble）
├── ModelContextWindowProfiles.java   # 模型窗口配置（qwenTurboDemo）
├── VehicleStatusProvider.java        # 车辆状态接口
└── provider/
    ├── RuntimeContextProvider.java
    ├── PersonaContextProvider.java
    ├── PromptContextProvider.java
    ├── UserInputContextProvider.java
    ├── IntentContextProvider.java
    ├── ToolGroupContextProvider.java
    ├── LongTermMemoryContextProvider.java
    ├── SessionMemoryContextProvider.java
    ├── CallerExtraContextProvider.java
    ├── VehicleStateContextProvider.java
    └── TimeContextProvider.java
```

### 4.2 11 个 Provider 及其职责

| Provider | 生命周期 | 可见性 | 输出内容 |
|----------|---------|--------|---------|
| RuntimeContextProvider | REQUEST_STATIC | TRACE_ONLY | 请求 ID/用户/会话/人格等运行时元信息 |
| PersonaContextProvider | REQUEST_STATIC | POLICY_ONLY | 解析后的人格标识（驱动 Prompt 选择） |
| PromptContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 渲染后的 System Prompt（CRITICAL） |
| UserInputContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 当前 UserMessage（CRITICAL） |
| IntentContextProvider | REQUEST_STATIC | POLICY_ONLY | 意图识别结果（驱动策略决策） |
| ToolGroupContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 真实 ToolSpecification |
| LongTermMemoryContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 长期记忆条目（UNTRUSTED_DATA, CONTEXT_DATA） |
| CallerExtraContextProvider | REQUEST_STATIC | MODEL_VISIBLE | 调用方附加信息（UNTRUSTED_DATA, OPTIONAL） |
| SessionMemoryContextProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 当前 session ChatMemory 快照 |
| VehicleStateContextProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 最新车辆状态 |
| TimeContextProvider | ITERATION_DYNAMIC | MODEL_VISIBLE | 当前时间（OPTIONAL） |

### 4.3 消息装配顺序

唯一 SystemMessage → \[可选 Context Data UserMessage] → Session ChatMemory（含当前 UserMessage 和历史）

Context Data 合并格式：
```
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

### 4.4 Trace 层级

```
agent.request
  agent.loop
    context.prepare
    context.assemble
    gen_ai.chat
    tool.execute
```

---

## 五、改进后 Context 模块实现的功能

### 5.1 统一上下文装配
Context 模块成为 TEXT 模型输入的唯一来源。AgentLoopOrchestrator 不再拼接 System Prompt、不运行为消息型 PreProcessor、不手工组装 allMessages。ChatRequest 的 messages 和 toolSpecifications 全部来自 ContextAssemblyResult。

### 5.2 真实数据驱动
所有 11 个 Provider 从能力模块读取真实数据：
- Prompt Provider 调用 PromptManager.render()
- UserInput Provider 使用 SpeakerMessageFormatter
- Tool Provider 通过 ToolRegistry.toolSpecificationsByNames() 解析真实规格
- Memory Provider 调用 MemoryOrchestrator.longTermMemorySnapshot() / sessionMemorySnapshot()
- Vehicle/Time Provider 按迭代动态获取最新状态

### 5.3 可见性控制
通过 ContextVisibility/MODEL_VISIBLE/POLICY_ONLY/TRACE_ONLY 三级可见性决定数据是否进入模型。运行时元信息（requestId/userId）默认 TRACE_ONLY，不进入 LLM。

### 5.4 信任级别隔离
TRUSTED_SYSTEM（Prompt）可进入 SystemMessage；UNTRUSTED_DATA（长期记忆、caller extra）只能进入 Context Data UserMessage，不能伪装 System 指令。

### 5.5 请求级/迭代级生命周期
REQUEST_STATIC Provider 针对每个请求执行一次（Prompt、UserInput、Tool）；ITERATION_DYNAMIC Provider 每轮 AgentLoop 迭代重新执行（Vehicle、Time、SessionMemory），确保工具执行后的状态变更在下一轮可见。

### 5.6 Token 预算
HeuristicContextTokenEstimator 提供保守估算（字符/2 + 结构开销 ×1.15）。ModelContextWindowProfiles.qwenTurboDemo() 提供 qwen-turbo Demo 配置（32768 窗口，2048 输出预留，1024 安全余量）。

### 5.7 Memory 压缩计划
MemoryOrchestrator 支持 planSessionCompaction() 生成压缩计划（不调用模型、不写 Store）和 executeCompactionPlan() 执行原子写回。SessionMemoryStore.replaceMessagesOrThrow() 使用 INSERT OR REPLACE 单事务替换，失败时抛出 MemoryPersistenceException。

### 5.8 错误码体系
Context 领域错误码（REQUIRED_PROVIDER_FAILED / TOOL_SPEC_RESOLUTION_FAILED / MESSAGE_SEQUENCE_INVALID / CONTEXT_BUDGET_EXCEEDED / MEMORY_COMPACTION_FAILED / CONTEXT_CANCELLED / CONTEXT_INTERNAL_ERROR）映射到 AgentResult.ErrorType，再由 RuntimeResponseMapper 映射为用户可见的错误消息。

---

## 六、遗留问题

### 6.1 AgentLoopOrchestrator 仍有两个 execute() 入口
旧的 `execute(String, Map)` 保留供 scene/VL 路径使用。TEXT 路径已完全走新 `execute(RequestSession, ContextPrepareResult)`。两条路径的构造器和依赖不同，不会混淆，但增加了类的复杂度。Phase 6 未做彻底分离。

### 6.2 旧 ContextSection 类型仍存在
ContextSection、ContextSectionType、ContextBuildResult、ContextDebugInfo 四个类型仍存留在代码中，由旧 `build()` 路径使用。所有 Provider 仍创建 `ContextSection` 对象（通过 `fromLegacySection()`）。这些类型和 `build()` 方法在 Phase 6 中未完整删除，因为会影响 11 个 Provider + ContextProviderResult + ContextFrame 等多个文件。

### 6.3 ContextBudgetManager 完整裁剪未接入
优先级裁剪（OPTIONAL → 长期记忆 → 工具 → 压缩 → 最旧历史 → CRITICAL 超限失败）的预算顺序已设计。`generateBudgetReport()` 和方法骨架已实现，但未接入实际装配循环。当前 `ContextMessageAssembler.assemble()` 不调用预算裁剪。

### 6.4 executeCompactionPlan() 未在生产链路启用
压缩执行路径（`MemoryOrchestrator.executeCompactionPlan()`）仅通过 JVM 单元测试验证，未在 AIAgentService 或 AgentLoopOrchestrator 的生产链路中调用。

### 6.5 AgentRuntime 空输入校验未启用
`AgentResult.ErrorType.INVALID_INPUT` 和 `RuntimeResponseMapper` 分支已就绪，但 AgentRuntime 未添加空输入前置校验。

### 6.6 gen_ai.chat / tool.execute span 仍挂在 root 下
`agent.loop` span 和 `context.prepare`/`context.assemble` span 已正确层级化。但 AgentLoopOrchestrator 中通过 `trace.startLlmCall()` 和 `trace.startTool()` 创建的 span 仍以 rootContext 为父，未迁移到 agent.loop scope。

### 6.7 非 TEXT 链路测试缺失
scene/VL/CONTROL 链路在此次改造中未做测试覆盖。Phase 6 的静态所有权审计也主要针对 TEXT 路径。

---

## 七、测试覆盖

| 测试包 | 覆盖内容 |
|--------|---------|
| `context.*` | ContextFrame 构建、Provider 执行、Orchestrator 装配、消息序列校验、Token 估算、预算裁剪 |
| `runtime.*` | AgentExecutor 强类型执行、AgentRuntime prepare/execute/cancel、ToolGroup 选择、Trace 写入 |
| `trace.*` | TraceSession parent-child 层级、AgentTraceRecorder 方法 |
| `memory.*` | MemorySnapshot sessionId 边界、MemoryCompactionPlan |

**最终验证结果：** `./gradlew clean testDebugUnitTest` — BUILD SUCCESSFUL
