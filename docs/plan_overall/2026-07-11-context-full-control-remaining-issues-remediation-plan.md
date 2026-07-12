# Context 全权控制剩余问题整改实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 关闭 Context 全权控制两轮验收中发现的全部剩余问题，使 TEXT 模型输入真正由 Context 统一生成、校验、预算和追踪，并完成可恢复的生产压缩与可验证的类型边界。

**Architecture:** Context 继续作为模型输入控制平面，负责 Provider 策略、最终消息和工具规格、预算裁剪、压缩触发及 Context Trace；Memory 继续拥有摘要算法、持久化和 ChatMemory cache；AgentLoop 继续拥有模型调用、SafetyGuard、工具执行、结果写入和循环终止。整改分四个 Phase，先处理小范围正确性和测试，再处理消息所有权与构造边界，随后完成预算压缩，最后清理迁移债务并做全链路验收。

**Tech Stack:** Java/Kotlin、Android Service/AIDL、LangChain4j 1.16.3、JUnit4、OpenTelemetry、SQLite ChatMemoryStore、现有 ToolRegistry/ToolGroup/Memory/Prompt 模块。

---

## 1. 计划依据与当前状态

### 1.1 依据文档

- `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md`
- `docs/review/context/2026-07-11-context-full-control-implementation-acceptance-review.md`
- `docs/review/context/2026-07-11-context-full-control-fix-recheck.md`
- `docs/act_summary/2026-07-11-context-full-control-15-issues-fix-summary.md`
- `docs/overview/context-module-overview.md`

### 1.2 已确认完成、不得回退的能力

- `AIAgentService` 已向生产 Context 注入真实 `ToolRegistry`。
- TEXT AgentLoop 已恢复 SafetyGuard -> ToolExecutor -> ToolResult 顺序。
- TEXT AgentLoop 成功及参数失败路径已结束 RUNNING 状态。
- `AgentRuntime` 已在 Context 前拒绝 null/blank TEXT 输入。
- TEXT 的真实 `ChatRequest.messages/toolSpecifications` 已从 `ContextAssemblyResult` 读取。
- `ContextMode`、`ContextBuildResult`、`ContextExtraPreProcessor` 已删除。
- LLM/Tool span 已显式使用当前 `agent.loop` parent。
- 当前强制验证基线：205 个 JVM 测试通过，`assembleDebug` 和 `lintDebug` 通过。

这些能力必须由新增回归测试锁定，不允许为简化后续整改重新启用 HYBRID、固定工具或 TEXT PreProcessor 注入。

---

## 2. 总体工作边界

### 2.1 Context 必须拥有的职责

- 执行 request-static 和 iteration-dynamic Provider，并执行 required/optional 策略。
- 生成每次模型调用的唯一 `List<ChatMessage>` 和 `List<ToolSpecification>`。
- 保证 System、Context Data、Session Messages 和 ToolExchange 的顺序及原子性。
- 统一估算模型输入预算，按优先级裁剪，并在必要时请求 Memory 执行一次持久化压缩。
- 记录 Provider outcome、预算、裁剪、压缩和失败原因到 `context.prepare/context.assemble`。
- 将稳定的 ContextErrorCode 传递到 AgentResult、RuntimeResult 和 AgentResponse。

### 2.2 Context 不接管的职责

- 不实现 Memory 摘要 Prompt、摘要模型调用、SQLite 写入算法或 cache 容器。
- 不执行 Tool，不实现 Tool 参数校验，不替代 SafetyGuard。
- 不调用主对话模型，不决定 AgentLoop 的终止次数或最终结果收集方式。
- 不读取 AIDL Binder、ActiveRequestRegistry 或 Android UI 状态。

### 2.3 本计划禁止事项

- 不修改 AIDL 字段、Launcher 协议、AgentRequest/AgentResponse 对外语义。
- 不修改具体车辆 Tool、VehicleStateMachine、SOA 或相机实现。
- 不引入网络 tokenizer、Robolectric、Mockito 或新的第三方依赖。
- 不随机截断单个 Tool Schema，也不在 allToolsFallback 中任意丢弃部分工具。
- 不删除 SCENE/VL 正在使用的旧 Prompt、PreProcessor 或非 TEXT execute；应通过类边界隔离，而不是强行共用 TEXT 架构。
- 不用扩大 `lint.xml` 忽略范围掩盖新错误。

### 2.4 固定取消语义

- prepare 前取消：不写当前 UserMessage。
- 当前 UserMessage 写入后、模型前取消：保留当前 UserMessage，不写 AiMessage/ToolResult。
- 模型调用期间取消：模型返回后、写 AiMessage 前再次检查；已取消则丢弃模型结果。
- 多工具批次执行期间取消：已完成的工具及结果保留；未执行 Tool Call 写入确定性的 cancelled ToolResult，仅用于闭合 ToolExchange，不再执行工具，也不再调用模型。
- cancelled ToolResult 属于一致性记录，不作为业务成功响应，不触发 MemoryExtractor。

### 2.5 子 Agent 执行规范

每个 Task 由空白记忆子 Agent 独立执行时，必须遵守以下固定流程：

1. 先读取本计划的总体边界、当前 Phase 边界、当前 Task 和它明确列出的生产/测试文件。
2. 再读取当前文件真实实现，不根据本计划中的行号猜测代码；行号会随前序 Task 改动而变化，类名和方法职责才是稳定定位依据。
3. 执行前运行当前 Task 指定的目标测试并记录基线；若基线已经失败，先报告失败，不把既有失败归因于本 Task。
4. 先增加能够失败的行为测试，再修改生产代码；禁止只修改测试期望来适配错误实现。
5. 只修改当前 Task 的文件和它直接导致的编译引用。发现其他问题时记录到交接说明，不顺手重构。
6. 当前 Task 完成后运行目标测试；当前 Phase 最后一个 Task 还必须运行该 Phase 的全量门禁。
7. 子 Agent 的交接结果必须列出：修改文件、关键行为变化、运行命令、测试数量/结果、未完成项、发现但未修改的问题。
8. 不允许删除失败测试、扩大 Lint 忽略、恢复 HYBRID/固定工具输入，或用空实现让测试通过。

### 2.6 跨 Task 契约冻结规则

- Task 1.1 确定 Memory 窄接口后，后续 Task 只能按已定义方法扩展，不得另建第二套 Memory Gateway。
- Task 1.2 确定 required 判定签名后，所有 Provider 必须使用同一签名，不允许同时保留无参和有参 required 语义。
- Task 1.3 确定 ContextErrorCode 映射后，后续 Phase 不得重新把领域错误压成 EXCEPTION。
- Task 1.4 确定 ToolExchange Validator 后，预算裁剪和压缩必须复用同一原子单元语义。
- Task 2.1 删除 Assembler 的 sessionMessages 参数后，后续代码不得重新从 AgentLoop 注入历史列表。
- Task 2.2 拆出 TextAgentLoop 后，所有 TEXT 新功能只进入新类，旧 AgentLoop 只接受非 TEXT 回归修复。
- Task 3.1 确定 TokenEstimator 后，Assembler、BudgetManager、Trace 和测试必须使用同一估算入口。

---

## 3. 文件责任映射

### 3.1 计划新增文件

| 文件 | 责任 |
|---|---|
| `app/src/main/java/com/hirain/aiagent/core/TextAgentLoopOrchestrator.java` | TEXT 专用循环，只接收 ContextAssemblyGateway 和 TEXT 所需执行依赖，不持有 PromptManager 或固定 ToolSpecification。 |
| `app/src/main/java/com/hirain/aiagent/memory/ContextMemoryGateway.java` | TEXT AgentLoop、Context Provider 和测试共用的窄 Memory 接口，暴露快照、session ChatMemory、turn 提取和压缩协议，不暴露 SQLite 细节。 |
| `app/src/main/java/com/hirain/aiagent/context/ContextBudgetDecision.java` | 表达预算后的最终消息、工具、裁剪记录、压缩建议和最终预算状态。 |
| `app/src/test/java/com/hirain/aiagent/core/TextAgentLoopOrchestratorTest.java` | Phase 2 类拆分后的最终 TEXT Loop 测试；由 Phase 1 的旧类真实执行测试迁移而来。 |
| `app/src/test/java/com/hirain/aiagent/context/ContextProviderRequiredPolicyTest.java` | required Provider 和 requiredContextKeys 的生产策略测试。 |
| `app/src/test/java/com/hirain/aiagent/context/ContextCancellationAtomicityTest.java` | 取消时 AiMessage/ToolExchange 原子性测试。 |
| `app/src/test/java/com/hirain/aiagent/context/ContextBudgetIntegrationTest.java` | 小预算下的裁剪顺序、Tool Schema 原子性和最终失败测试。 |
| `app/src/test/java/com/hirain/aiagent/context/ContextCompressionIntegrationTest.java` | plan/execute/cache 失效/重读/二次 assemble 集成测试。 |
| `app/src/test/java/com/hirain/aiagent/trace/ContextProductionTraceHierarchyTest.java` | request -> loop -> context/LLM/tool/memory 的真实 parentSpanId 测试。 |

本计划明确标记为 Create 的类型和测试均需创建；明确标记为 Modify 的现有测试应直接扩展，不再由执行者临时改变文件归属。

**后续 Task 路径约定：** `core/...`、`context/...`、`memory/...`、`runtime/...`、`trace/...` 均相对于 `app/src/main/java/com/hirain/aiagent/`；测试路径均相对于 `app/src/test/java/com/hirain/aiagent/`。`AIAgentService.kt` 的完整路径为 `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`。

### 3.2 主要修改文件

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopState.java`
- `app/src/main/java/com/hirain/aiagent/core/AgentResult.java`
- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`
- `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextProviderResult.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextAssemblyRequest.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextAssemblyResult.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageAssembler.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextMessageSequenceValidator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextBudgetManager.java`
- `app/src/main/java/com/hirain/aiagent/context/HeuristicContextTokenEstimator.java`
- `app/src/main/java/com/hirain/aiagent/context/ContextTraceRecorder.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/RuntimeContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/PromptContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/ToolGroupContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/SessionMemoryContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/context/provider/VehicleStateContextProvider.java`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`

### 3.3 子 Agent 任务依赖与交接顺序

```text
Task 1.1 -> Task 1.2 -> Task 1.3 -> Task 1.4
    |
    +----> Task 1.5（可在 1.1 完成后独立执行）

Phase 1 gate
    -> Task 2.1 -> Task 2.2 -> Task 2.3
    -> Phase 2 gate
    -> Task 3.1 -> Task 3.2 -> Task 3.3
    -> Phase 3 gate
    -> Task 4.1 -> Task 4.2 -> Task 4.3 -> Task 4.4
    -> final gate
```

- 同一时间最多并行 Task 1.5 与 1.2/1.3；其余 Task 存在接口或文件依赖，必须顺序执行。
- 每个新子 Agent 开始前必须读取上一 Task 的交接说明和当前 `git diff`，不能假设计划中的初始签名仍未变化。
- 子 Agent 不创建自己的替代接口或临时兼容层；若前序实现与冻结契约不一致，应停止并回报主 Agent，而不是同时保留两套 API。
- Phase gate 由主 Agent执行和审查，普通 Task 子 Agent不得自行宣布整个 Phase 完成。

---

## 4. Phase 1：小范围正确性、错误语义与真实主链测试

**阶段目标：** 在不接入压缩、不删除大批旧类型的前提下，先关闭 required、取消、ToolExchange、错误映射和并发状态问题，并建立真正执行 TEXT AgentLoop 的测试门禁。

### 4.1 本阶段工作边界

**允许修改：** Provider required 判定、取消检查点、错误码映射、消息校验器、AgentLoopState、TEXT Loop 的可测试依赖入口和 JVM 测试。

**禁止修改：** 不做预算裁剪、不调用压缩模型、不删除 ContextSection、不迁移 SCENE/VL、不改变 ToolGroupSelector 选择规则。

**阶段退出状态：** 三个原 P0 有真实回归测试；required 失败和取消不会调用模型；任何持久化 ToolExchange 都完整闭合；具体 ContextErrorCode 能到达 AgentResponse。

### Task 1.1：建立可执行的 TEXT Loop 测试入口

**前置依赖：** 无。本 Task 是后续所有 TEXT 行为整改的测试基础，必须最先完成。

**生产文件：**

- Create: `memory/ContextMemoryGateway.java`
- Modify: `memory/MemoryOrchestrator.java`
- Modify: `core/AgentLoopOrchestrator.java`（迁移期间）
- Modify: `AIAgentService.kt`
- Test/Rewrite: `core/AgentLoopOrchestratorTextPathTest.java`

**实施要求：**

- [ ] 定义 `ContextMemoryGateway` 为生产和 JVM fake 共用的窄接口，固定包含以下职责，不暴露 Store、SQLiteOpenHelper 或 Android Context：
  - `chatMemoryForSession(sessionId, maxMessages)`：返回该 session 的 live LangChain4j ChatMemory。
  - `sessionMemorySnapshot(sessionId, maxMessages)`：返回不可变 MemorySnapshot，供 Context Provider 每轮读取。
  - `longTermMemorySnapshot(userId)`：返回用户级不可变 LongTermMemorySnapshot，供长期记忆 Provider 读取。
  - `extractTurnMemory(userId, sessionId, userMessage, aiResponse, trace)`：只执行长期记忆提取，不执行阈值压缩。
  - `planSessionCompaction(sessionId, targetTokens)` 与 `executeCompactionPlan(plan, trace)`：Phase 3 使用；Phase 1 的 fake 可以返回 not-required/not-executed 结果，但不得返回 null。
- [ ] `MemoryOrchestrator` 实现该接口；现有 `chatMemoryForSession`、`sessionMemorySnapshot`、`planSessionCompaction`、`executeCompactionPlan` 直接满足对应方法，仅新增“只提取、不压缩”的 turn 完成入口。
- [ ] Gateway 的生产行为继续委托现有 `SessionChatMemoryProvider`、MemoryExtractor 和 Store，不在接口适配层复制算法。
- [ ] 在旧 AgentLoop 中增加迁移期 TEXT 专用构造器 `AgentLoopOrchestrator(AgentConfig, ContextMemoryGateway, ContextAssemblyGateway)`；该构造器不创建 fallbackChatMemory，也不接收 Android Context、PromptManager 或固定 ToolSpecification。
- [ ] 旧类保留原 `MemoryOrchestrator` 字段供 `execute(String, Map)` 使用，并新增 `ContextMemoryGateway` 字段只供强类型 TEXT execute 使用；不得把 legacy 字段改成 Gateway 后再扩充非 TEXT 方法。
- [ ] AIAgentService 的 TEXT 生产装配切换到迁移期 TEXT 专用构造器；chat/sceneOrchestrator 保持旧构造器。
- [ ] 测试 fake 使用 LangChain4j `MessageWindowChatMemory`，不依赖 Android Context 或 SQLite。
- [ ] 将现有只验证 Runtime lambda 的测试重写为真实调用 `AgentLoopOrchestrator.execute(RequestSession, ContextPrepareResult)`；在 Phase 2 拆类后再迁移到最终测试类名。
- [ ] 测试必须真实构造 Loop、ContextAssemblyGateway、CapturingModelCaller、ToolExecutor、SafetyGuard、Terminator 和 ResultCollector。
- [ ] 覆盖连续执行两次均成功，第二次不返回 `Agent is busy`。
- [ ] 覆盖高速开门 SafetyGuard veto，ToolExecutor 调用次数为 0。
- [ ] 覆盖 ToolRegistry 选中工具原样进入 CapturingModelCaller 捕获的 ChatRequest。
- [ ] 覆盖 ToolExecutor 异常产生配对错误 ToolResult，下一轮模型可继续生成文本结果。

**执行顺序：**

1. 先创建 Memory Gateway 和纯内存 fake，使现有 AgentLoop 可以在 JVM 中取得 session ChatMemory。
2. 增加独立的 TEXT-only 构造路径和 textMemoryGateway 字段，保持 legacy MemoryOrchestrator 字段不变。
3. 重写 `AgentLoopOrchestratorTextPathTest`，不得通过 AgentRuntime lambda 绕过 Loop。
4. 使用 CapturingModelCaller 预置“文本回复”“单工具调用后文本回复”“多工具调用”响应队列。
5. 分别注入计数 ToolExecutor、可配置 SafetyGuard、NoToolCallTerminator 和 DirectTextCollector，断言每个组件的调用次数和顺序。

**禁止越界：** 本 Task 不拆分类、不改变 Context Provider、不实现压缩、不修改 SCENE/VL execute。

**完成证据：** 测试输出中必须能看到真实 `AgentLoopOrchestratorTextPathTest` 用例数量；至少包含连续两次执行、Safety veto、工具成功、工具异常四类用例，不能只断言 RuntimeResult.success；Service TEXT 装配不再触发 deprecated constructor warning。

### Task 1.2：统一 required Provider 策略

**前置依赖：** Task 1.1 已提供可执行 TEXT Loop 和 Memory fake。

**生产文件：**

- Modify: `context/ContextProvider.java`
- Modify: `context/ContextBuildInput.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `context/ContextProviderResult.java`
- Modify: `context/provider/RuntimeContextProvider.java`
- Modify: `context/provider/PromptContextProvider.java`
- Modify: `context/provider/ToolGroupContextProvider.java`
- Modify: `context/provider/LongTermMemoryContextProvider.java`
- Modify: `context/provider/SessionMemoryContextProvider.java`
- Modify: `context/provider/VehicleStateContextProvider.java`
- Modify: `AIAgentService.kt`
- Test/Create: `context/ContextProviderRequiredPolicyTest.java`

**实施要求：**

- [ ] 将 `ContextProvider.required()` 统一替换为 `required(RequestSession session, ContextBuildInput input)`；删除无参默认方法，避免调用者误用固定 required。
- [ ] ContextBuildInput 的 Memory 依赖类型统一改为 `ContextMemoryGateway`，builder/getter 命名统一为 `memoryGateway`；AIAgentService 注入现有 MemoryOrchestrator 实例，Provider 不再依赖具体 MemoryOrchestrator 类。
- [ ] LongTermMemoryContextProvider 和 SessionMemoryContextProvider 分别只调用 Gateway 的长期/短期快照方法；不得向下强转 MemoryOrchestrator。
- [ ] ContextProviderResult 增加唯一的 `ContextProviderStatus status` 字段和 getter；迁移期 `success()/fallback()` 必须由 status 派生，禁止同时维护可能不一致的 boolean 状态。
- [ ] ContextOrchestrator 必须先取得 ProviderResult，再根据同一个 session/input 计算 required；判定条件统一为“required=true 时只有 SUCCESS 可以继续，FALLBACK/FAILED 均终止”。FALLBACK 只允许用于 optional Provider 的安全降级。
- [ ] Runtime Provider 在 requestId/sessionId/userId/personaId 等 TEXT 必需身份缺失时返回稳定 failure。
- [ ] Prompt Provider 始终 required；PromptManager 缺失、模板不存在、渲染异常或结果为空均返回 FAILED，不允许空 Prompt FALLBACK。
- [ ] UserInput Provider 保持 required，并拒绝空白输入；Runtime 仍是权威第一道空输入门禁。
- [ ] Tool Provider 对 CHAT_ONLY 返回成功空集合；allToolsFallback 返回完整启用集合；明确 toolName 解析失败或 ToolRegistry 缺失时返回 `TOOL_SPEC_RESOLUTION_FAILED`。
- [ ] SessionMemory Provider 在 TEXT Context 链中始终 required；MemoryOrchestrator/sessionId 缺失或快照读取异常返回 failure。
- [ ] Vehicle Provider 查询选中 ToolGroup 的 requiredContextKeys；包含 `vehicle_status` 时，车辆状态缺失或读取异常返回 required failure，否则保持 optional fallback。
- [ ] prepare 和 assemble 都执行 required 规则；动态 Provider 失败不能只记录后继续。
- [ ] required failure 返回前保留已执行 Provider outcomes，便于 Trace 和诊断。

**Provider 决策矩阵：**

| Provider | required 条件 | SUCCESS | FALLBACK | FAILED |
|---|---|---|---|---|
| Runtime | TEXT 始终 required | 必需 identity 完整 | 不允许 | identity 缺失 |
| Prompt | 始终 required | 非空模板已渲染 | 不允许 | manager/模板/渲染/空结果异常 |
| UserInput | 始终 required | 非空 current UserMessage | 不允许 | null/blank/构造失败 |
| Tool | 非 CHAT_ONLY 时 required | 选中集合或 all fallback 完整解析 | CHAT_ONLY 的空集合属于 SUCCESS，不记 FALLBACK | registry 缺失或明确名称解析失败 |
| SessionMemory | TEXT 链始终 required | 快照读取成功，允许消息列表为空 | 不允许 | gateway/sessionId/读取异常 |
| VehicleState | requiredContextKeys 含 vehicle_status 时 required | 快照非空 | 非 required 场景允许空状态 | required 场景缺失或读取异常 |
| Time/LongTerm/CallerExtra | optional | 数据可用 | 缺失或读取失败 | 仅不可恢复的契约异常 |

**执行顺序：**

1. 修改 ContextProvider 签名并修复全部 Provider 编译引用。
2. 先实现 Prompt/UserInput 的固定 required，再实现 Tool/Vehicle 的请求级 required。
3. 最后修改 prepare 和 assemble 的统一终止逻辑，并确保动态 Provider 走相同分支。
4. 每增加一个 Provider 策略就运行对应测试类，避免一次修改全部 Provider 后无法定位失败。

**关键测试：**

- [ ] PromptManager=null、render throw、empty prompt 三种情况模型零调用。
- [ ] CHAT_ONLY 无工具成功；明确工具缺失失败；allToolsFallback 返回完整启用工具。
- [ ] SessionMemory 读取失败模型零调用。
- [ ] DOOR/CHASSIS 缺 vehicle_status 失败；普通聊天车辆状态缺失可降级。

**禁止越界：** 不修改 ToolGroupSelector 的匹配结果，不把 optional Provider 全部升级为 required，不在 Provider 内调用模型或写 Store。

**完成证据：** 测试必须同时断言 ContextErrorCode、失败 Provider 名称、ModelCaller 调用次数为 0，以及 optional fallback 仍能产生成功 AssemblyResult。

### Task 1.3：修复取消原子性和错误映射

**前置依赖：** Task 1.2 已让 prepare/assemble 返回稳定 ContextErrorCode。

**生产文件：**

- Modify: `core/AgentLoopOrchestrator.java`（迁移期间）
- Modify: `core/AgentResult.java`
- Modify: `runtime/AgentRuntime.java`
- Modify: `runtime/RuntimeResult.java`
- Modify: `runtime/RuntimeResponseMapper.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `context/ContextAssemblyResult.java`
- Test/Create: `context/ContextCancellationAtomicityTest.java`
- Test: `runtime/AgentRuntimeContextTest.java`
- Test: `runtime/RuntimeResponseMapperTest.java`

**实施要求：**

- [ ] 模型调用返回后、`chatMemory.add(aiMessage)` 前再次检查 cancel；已取消时丢弃 AiMessage 并返回 CANCELLED。
- [ ] assemble 在每个动态 Provider 之间、Assembler 前和返回前检查取消。
- [ ] 多工具批次取消时，不再执行剩余工具；为每个未执行 request 写入 `cancelled_before_execution` ToolResult，闭合已持久化 Ai tool request。
- [ ] 取消一致性 ToolResult 不进入 ToolExecutionRecord 的成功统计，不触发 MemoryExtractor，也不再次调用模型。
- [ ] AgentLoop 根据 `ContextAssemblyResult.errorCode()` 映射 CONTEXT_CANCELLED、TOOL_SPEC_RESOLUTION_FAILED、MEMORY_COMPACTION_FAILED、MESSAGE_SEQUENCE_INVALID 和通用构建失败。
- [ ] `AgentRuntime.failureFromPrepare()` 不再包装成普通 EXCEPTION；保留原 ContextErrorCode 对应的 Runtime errorType。
- [ ] RuntimeResponseMapper 对每个公开错误类型提供稳定 status/text/errorDetail，不泄漏底层异常栈。

**固定错误映射：**

| ContextErrorCode | AgentResult/Runtime errorType | 对外语义 |
|---|---|---|
| CONTEXT_CANCELLED | CANCELLED | 请求取消 |
| CONTEXT_BUDGET_EXCEEDED | CONTEXT_BUDGET_EXCEEDED | 输入超过预算且无法恢复 |
| REQUIRED_PROVIDER_FAILED | REQUIRED_PROVIDER_FAILED | 必需上下文来源失败 |
| TOOL_SPEC_RESOLUTION_FAILED | TOOL_SPEC_RESOLUTION_FAILED | 工具规格无法解析 |
| MESSAGE_SEQUENCE_INVALID | MESSAGE_SEQUENCE_INVALID | 消息或 ToolExchange 非法 |
| MEMORY_COMPACTION_FAILED | MEMORY_COMPACTION_FAILED | 压缩是满足预算的必要条件但执行失败 |
| CONTEXT_INTERNAL_ERROR | CONTEXT_BUILD_FAILED | Context 内部错误 |

- [ ] 若 AgentResult.ErrorType 缺少上表枚举，则在 `AgentResult.java` 增加同名值；RuntimeResult 使用枚举名字符串，不另建第二套字符串常量。
- [ ] RuntimeResponseMapper 为新增错误提供稳定中文文本，但 errorDetail 保留脱敏后的 Provider/阶段信息。

**取消检查点顺序：**

1. AgentRuntime 调用 prepare 前。
2. prepare 每个 static Provider 前后。
3. AgentLoop 写 current UserMessage 后、调用 assemble 前。
4. assemble 每个 dynamic Provider 前后及 Assembler 前后。
5. 主模型调用前和模型返回后、写 AiMessage 前。
6. 工具批次开始前、每个 ToolExecutor 前、每个 ToolExecutor 返回后。
7. MemoryExtractor 前和最终 ResultCollector 前。

**测试场景必须分别控制取消发生时点：** cancel-before-prepare、cancel-after-user-write、cancel-during-dynamic-provider、cancel-during-model-call、cancel-between-two-tools、cancel-before-result。每个场景都断言 ModelCaller/ToolExecutor/MemoryGateway 调用次数和最终 ChatMemory 内容。

**禁止越界：** 不尝试线程中断闭源模型或已开始执行的车控 Tool；本 Task 只保证不启动新的副作用、抑制迟到写入并闭合已持久化 ToolExchange。

**完成证据：** 六类取消测试全部通过，且 RuntimeResponseMapper 对上表每个 errorType 至少有一个映射测试。

### Task 1.4：严格校验 System 与 ToolExchange

**前置依赖：** Task 1.3 已固定取消时未执行工具的 cancelled ToolResult 格式。

**生产文件：**

- Modify: `context/ContextMessageSequenceValidator.java`
- Modify: `context/ContextMessageAssembler.java`
- Test: `context/ContextMessageSequenceValidatorTest.java`
- Test: `context/ContextMessageAssemblerTest.java`

**实施要求：**

- [ ] Validator 使用 requestId -> toolName 的 pending map，不再使用全局 foundToolCall/toolName 集合。
- [ ] 每个 ToolResult 的 id 必须存在、名称必须匹配，且同一 id 只能消费一次。
- [ ] 遇到下一条 UserMessage、普通 AiMessage 或序列结束时，pending map 必须为空。
- [ ] 重复 ToolResult、错误 id、错误 name、结果缺失和跨轮误配均返回 MESSAGE_SEQUENCE_INVALID。
- [ ] 最终 TEXT 消息必须且只能包含一个非空 SystemMessage，并且必须在首位。
- [ ] 多个 System Contribution 不再 warning 后忽略，而是明确失败，避免 Provider 冲突被隐藏。
- [ ] 删除 `noSystemMessage_assemblesWithoutSystem` 的旧期望，替换为缺 System 失败测试。

**Validator 实现算法：**

1. 首先校验 messages 非空，索引 0 是唯一且非空的 SystemMessage。
2. 遍历消息时维护 `LinkedHashMap<String, PendingToolCall>`；PendingToolCall 保存 requestId、toolName 和所属 AiMessage 索引。
3. 遇到带 ToolExecutionRequest 的 AiMessage 时，要求当前 pending 为空，再把该 AiMessage 的全部 request 按 id 加入；空 id 或重复 id 立即失败。
4. 遇到 ToolExecutionResultMessage 时，按 id 从 pending 查找；不存在、名称不一致或已经消费均失败；成功后移除。
5. pending 非空时禁止出现 UserMessage、普通 AiMessage、SystemMessage 或序列结束。
6. cancelled/error ToolResult 与普通结果使用相同 id/name 原子规则，不因状态文本不同而绕过校验。

**必须新增的测试名称/场景：** wrong-id、wrong-name、duplicate-result、missing-second-result、two-tool-complete、same-tool-name-different-id、new-user-before-results、duplicate-system、empty-system、missing-system。

**禁止越界：** Validator 只验证结构，不解析工具结果 JSON 业务内容，不修复或删除历史消息。

**完成证据：** 上述非法场景全部抛出 InvalidMessageSequenceException，并由 Assembler 稳定转换为 MESSAGE_SEQUENCE_INVALID；合法多工具和 cancelled ToolResult 场景通过。

### Task 1.5：修复 AgentLoopState 原子抢占

**前置依赖：** Task 1.1 已能连续执行真实 Loop。

**生产文件：**

- Modify: `core/AgentLoopState.java`
- Test: `core/AgentLoopStateTest.java`

**实施要求：**

- [ ] `tryStart()` 使用 CAS 从任意非 RUNNING 终态切换到 RUNNING，不再使用 get 后 set。
- [ ] 两线程同时抢占时只能一个成功；测试使用 CountDownLatch 同步起跑并断言成功数为 1。
- [ ] 保持 COMPLETED/ERROR/TIMEOUT 后允许下一次启动的既有语义。

**实现约束：** 使用 CAS 重试循环读取当前 ordinal；看到 RUNNING 立即返回 false，其他状态仅在 `compareAndSet(observed, RUNNING)` 成功后更新时间字段。不得先 set RUNNING 再更新时间，也不得使用 synchronized 包住整个 AgentLoop。

**完成证据：** 并发测试重复运行至少 100 轮，每轮两个线程只有一个 tryStart 成功；顺序状态测试证明 COMPLETED/ERROR/TIMEOUT 均可再次启动。

### Phase 1 验证

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorTextPathTest" --tests "com.hirain.aiagent.context.ContextProviderRequiredPolicyTest" --tests "com.hirain.aiagent.context.ContextCancellationAtomicityTest" --tests "com.hirain.aiagent.context.ContextMessageSequenceValidatorTest" --tests "com.hirain.aiagent.runtime.*"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

**预期：** 目标测试和全量 JVM 测试通过；Debug 构建通过；没有模型调用发生在 required failure 或取消之后；三个原 P0 均有真实 Loop 回归保护。

---

## 5. Phase 2：消息来源统一、TEXT 专用循环与 Context Trace

**阶段目标：** 让 Message Contribution 成为最终消息的真实来源，移除 TEXT 对旧 Prompt/固定工具构造器的依赖，并把 Context 诊断接入生产 Trace。

### 5.1 本阶段工作边界

**允许修改：** TEXT Loop 类拆分、ContextAssemblyRequest 契约、Message Contribution 消费、ContextTraceRecorder 生产接线。

**禁止修改：** 不删除 SCENE/VL 旧 execute，不修改非 TEXT Prompt 行为，不开始生产压缩，不批量删除 ContextSection。

**阶段退出状态：** TEXT Service 不再构造旧 AgentLoop；SessionMemory Provider 是唯一历史消息来源；Context Trace 能展示静态/动态 Provider、预算和失败结果。

### Task 2.1：让 Message Contribution 成为唯一消息来源

**前置依赖：** Phase 1 required/Validator 已稳定；SessionMemory 读取失败会可靠中止。

**生产文件：**

- Modify: `context/ContextAssemblyRequest.java`
- Modify: `context/ContextMessageAssembler.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `context/provider/UserInputContextProvider.java`
- Modify: `context/provider/SessionMemoryContextProvider.java`
- Modify: `core/AgentLoopOrchestrator.java`（迁移期间）
- Test: `context/ContextMessageAssemblerTest.java`
- Test: `context/ContextOrchestratorTest.java`

**实施要求：**

- [ ] Assembler 消费 `SOURCE_SESSION_MEMORY` Message Contribution，不再接收独立 `sessionMessages` 参数。
- [ ] `ContextAssemblyRequest` 删除 sessionMessages；保留 session、frame、iteration、budgetPolicy、cancelChecker 和单次压缩状态。
- [ ] SessionMemory Provider 每轮从 Memory 快照读取完整消息，成为 SESSION_MESSAGES 唯一来源。
- [ ] UserInput Provider 仍在 prepare 创建 currentUserMessage；AgentLoop 只写该对象，不允许从 session.userInput 再构造 fallback。
- [ ] CURRENT_USER Contribution 不直接由 Assembler追加，避免与已写入 SessionMemory 重复；测试明确 current user 只出现一次。
- [ ] 检测同一 messageSource 的重复 required Contribution 并失败，不能按注册顺序静默选择。
- [ ] 删除 AgentLoop 传入历史消息和 Assembler 手工追加历史的旧路径。

**目标方法契约：**

- `ContextAssemblyRequest` 不再包含 `List<ChatMessage> sessionMessages`。
- Phase 2 的 Assembler 入口固定为 `assemble(ContextFrame mergedFrame, ContextBudgetPolicy budgetPolicy)`；Frame 中必须已经包含 static + 本轮 dynamic Contributions。
- Assembler 只把 `SOURCE_SESSION_MEMORY` 的 Message Contribution 展开到最终历史区域；`SOURCE_CURRENT_USER` 只用于 prepareResult/current User 写入，不直接再次追加。

**固定消息顺序：** 唯一 SystemMessage -> 合并后的 Context Data UserMessage（存在时）-> SessionMemory Contribution 中的原始 LangChain4j 消息序列。Assembler 不重建 AiMessage/ToolResult，也不改变其 id。

**执行顺序：**

1. 先增加 Assembler 直接消费 SessionMemory Contribution 的测试。
2. 修改 Assembler 签名和消费逻辑。
3. 修改 ContextOrchestrator 构造 mergedFrame 的逻辑。
4. 最后删除 ContextAssemblyRequest.sessionMessages 及 AgentLoop 传参，处理全部编译引用。

**必须测试：** 空历史、单轮历史、多轮工具历史、当前用户只出现一次、两个 SessionMemory Contribution 冲突、Provider 快照与 AgentLoop 本地快照不同。最后一个场景必须证明最终请求采用 Provider 快照。

**禁止越界：** 不让 Assembler 调用 MemoryGateway；数据库读取仍只发生在 SessionMemory Provider。

**完成证据：** 全仓搜索 `sessionMessages()` 和 Assembler 三参数旧签名为零；ChatRequest 捕获结果与 SessionMemory Contribution 内容一致。

### Task 2.2：拆分 TEXT 专用 AgentLoop

**前置依赖：** Task 2.1 已删除 AgentLoop 对历史列表的输入控制权。

**生产文件：**

- Create: `core/TextAgentLoopOrchestrator.java`
- Modify: `core/AgentLoopOrchestrator.java`
- Modify: `AIAgentService.kt`
- Modify: `core/factory/AgentConfigFactory.java`
- Modify: `runtime/AgentExecutor.java`
- Test/Create: `core/TextAgentLoopOrchestratorTest.java`
- Test/Delete after migration: `core/AgentLoopOrchestratorTextPathTest.java`
- Test: `core/AgentLoopOrchestratorTest.java` 或现有非 TEXT 特征测试

**实施要求：**

- [ ] 将 `execute(RequestSession, ContextPrepareResult)` 及其 TEXT 状态移入 `TextAgentLoopOrchestrator`。
- [ ] 把 Phase 1 中真实执行旧类的全部 TEXT 测试迁移到 `TextAgentLoopOrchestratorTest`，迁移前后断言保持一致，再删除旧测试类。
- [ ] TEXT 构造器只接收 AgentConfig、ContextMemoryGateway 和 ContextAssemblyGateway；不得接收 Android Context、PromptManager 或固定 ToolSpecification。
- [ ] `AIAgentService.textOrchestrator` 从 Phase 1 的迁移期旧类切换为新类型，构造依赖仍保持 AgentConfig + ContextMemoryGateway + ContextAssemblyGateway。
- [ ] 旧 `AgentLoopOrchestrator` 只服务 SCENE/VL/旧 chat 路径，保留其 PromptManager、effectiveToolSpecs 和 buildSystemPromptMessage。
- [ ] 两个类共享的纯执行辅助逻辑只在确有重复时提取，不建立大型基类或新的通用框架。
- [ ] AgentRuntime 继续只依赖 AgentExecutor，不感知两个 Loop 的具体类型。

**TextAgentLoopOrchestrator 固定构造契约：**

```text
TextAgentLoopOrchestrator(
    AgentConfig config,
    ContextMemoryGateway memoryGateway,
    ContextAssemblyGateway contextAssemblyGateway)
```

构造器必须校验三个依赖非空。新类允许依赖 LangChain4j ChatMemory/ChatRequest、AgentConfig 的执行组件和 Context 结果类型；禁止 import Android Context、PromptManager、SystemMessage、PreProcessor 实现或构造期 ToolSpecification 列表。

**代码迁移边界：**

- 移动新 TEXT execute、TEXT 状态、取消/预算/工具循环和 extractTraceSession 所需逻辑。
- 旧类保留 `execute(String, Map)`、fallbackChatMemory、PromptManager、effectiveToolSpecs、buildSystemPromptMessage 和非 TEXT Trace。
- 不在两个类间共享可变 AgentLoopState；每个实例拥有独立状态。
- AIAgentService 的 `textOrchestrator` 字段和创建代码使用新类；`chatOrchestrator/sceneOrchestrator` 保持旧类；删除旧类中迁移期 TEXT 构造器。

**必须测试：** 新类不传 ContextAssemblyGateway 时构造失败；Service wiring 静态测试确认 TEXT 新类、SCENE 旧类；迁移前 Phase 1 的每个行为测试迁移后结果完全一致。

**完成证据：** 全仓 TEXT 生产装配只创建 TextAgentLoopOrchestrator，不再向 Loop 传 PromptManager 或 `toolRegistry.toolSpecifications`；旧类中迁移期 TEXT 构造器和强类型 TEXT execute 已删除。

### Task 2.3：接通 ContextTraceRecorder 和 Provider outcomes

**前置依赖：** Task 2.2 已建立最终 TEXT Loop 和 Trace scope 调用位置。

**生产文件：**

- Modify: `context/ContextOrchestrator.java`
- Modify: `context/ContextTraceRecorder.java`
- Modify: `context/ContextAssemblyResult.java`
- Modify: `trace/TraceSpanNames.java`
- Modify: `trace/AgentTraceRecorder.java`
- Test/Create: `trace/ContextProductionTraceHierarchyTest.java`
- Test: `context/ContextTraceRecorderTest.java`

**实施要求：**

- [ ] ContextOrchestrator 从 RequestSession 的 TraceContext 构造 ContextTraceRecorder，不再直接散写 GlobalOpenTelemetry 属性。
- [ ] context.prepare 记录 static provider 的 success/fallback/failed/required、贡献数量和耗时。
- [ ] context.assemble 记录 iteration、dynamic provider outcomes、消息数、工具数、预算、裁剪动作、压缩状态和最终 errorCode。
- [ ] prepare/assemble span 都使用当前 agent.loop 为显式 parent，并在异常路径标记 ERROR/recordException。
- [ ] 合并 static 与 dynamic outcomes 到 ContextAssemblyResult，不返回 Assembler 内部创建的空列表。
- [ ] LLM/Tool/Memory span 保持 agent.loop 子节点；不要错误地把完整模型调用包进已结束的 context.assemble span。
- [ ] 测试直接断言真实 parentSpanId 和关键属性，不只断言 span name 常量存在。

**固定 Trace 属性：**

- `context.prepare`：request/session 的脱敏关联值、provider.count、provider.required、provider.success/fallback/failed、contribution.count、duration.ms、error.code。
- `context.assemble`：iteration、message.count、tool.count、tokens.before/after/max、budget.within、trim.action.count、compression.recommended/executed/success、provider.dynamic.*、error.code。
- Provider 明细使用 span event 或受控 attributes，只记录 providerName/status/errorCode/duration，不记录 Prompt 正文、用户输入、长期记忆正文或完整车辆 JSON。

**生命周期要求：** ContextOrchestrator 通过 try/finally 结束 span；成功设置 OK，领域失败或异常设置 ERROR。prepare/assemble span 只覆盖 Context 工作，LLM/Tool span 作为 agent.loop 的后续兄弟节点，不错误嵌套在已结束 Context span 下。

**必须测试：** 正常聊天、optional fallback、required failure、预算超限、一次工具调用五条生产链；每条直接断言 span name、parentSpanId、status 和关键属性。

**完成证据：** 生产代码存在 ContextTraceRecorder 的真实构造和调用点；ContextAssemblyResult.providerOutcomes 同时包含 static 与本轮 dynamic outcomes。

### Phase 2 验证

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.*" --tests "com.hirain.aiagent.core.TextAgentLoopOrchestratorTest" --tests "com.hirain.aiagent.trace.ContextProductionTraceHierarchyTest"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

**预期：** Context Message Contribution 是唯一消息来源；TEXT Service 不再引用 deprecated 构造器；真实 Trace 层级和 outcomes 测试通过；SCENE/VL 编译及既有测试不变。

---

## 6. Phase 3：完整预算裁剪与生产压缩闭环

**阶段目标：** 把现有“估算后直接失败”升级为完整预算治理，并按固定协议执行单请求最多一次的 Memory 压缩、cache 失效、重读和二次 assemble。

### 6.1 本阶段工作边界

**允许修改：** ContextBudgetManager/Estimator/Report、ContextOrchestrator assemble 流程、Memory 压缩窄接口、TEXT Loop 压缩状态传递。

**禁止修改：** 不引入精确 tokenizer；不把摘要算法移入 Context；不部分截断 Tool Schema；不让 Memory 决定 System/Tool/Context Data 的全局预算。

**阶段退出状态：** 小预算测试可重复证明裁剪顺序；Memory 压缩最多一次并原子写回；重读后重新装配；最终仍超限时稳定失败且模型零调用。

### Task 3.1：统一 Token 估算入口

**前置依赖：** Phase 2 最终 Assembler 已只消费 Context Contributions。

**生产文件：**

- Modify: `context/HeuristicContextTokenEstimator.java`
- Modify: `context/ContextMessageAssembler.java`
- Modify: `context/ContextBudgetManager.java`
- Modify: `context/ContextBuildInput.java`
- Test: `context/HeuristicContextTokenEstimatorTest.java`

**实施要求：**

- [ ] Assembler 和 BudgetManager 统一依赖 ContextTokenEstimator，不再保留第二套静态估算实现。
- [ ] Tool 估算包含 name、description 和 `ToolSpecification.parameters()` 的稳定序列化结果。
- [ ] 消息估算覆盖 System/User/Ai 文本、ToolExecutionRequest 参数和 ToolExecutionResult 内容。
- [ ] 保持字符/2、结构开销和 1.15 安全系数的 Demo 策略，不宣称等于 Qwen tokenizer。
- [ ] 测试证明增加复杂 Tool parameter schema 会增加估算值，中文、英文和工具调用均非零。

**目标调用关系：** ContextOrchestrator 从 ContextBuildInput 取得唯一 ContextTokenEstimator，传给 Assembler/BudgetManager；ContextMessageAssembler 删除静态 `estimateTokens()`，ContextBudgetManager 删除任何 `String.valueOf(messages)` 回退估算。

**Tool Schema 估算规则：** 对 name、description、`parameters().toString()` 分别计数并加结构开销；parameters 为空仍保留固定结构开销。不得通过反射访问 LangChain4j 内部字段。

**消息估算规则：** 按 ChatMessage 类型读取稳定公开字段；AiMessage 的 text 与每个 ToolExecutionRequest 的 id/name/arguments 都计入，ToolExecutionResult 的 id/name/text 都计入。

**完成证据：** 全仓生产代码只有 HeuristicContextTokenEstimator 一个估算实现；同一 messages/tools 在 Assembler、BudgetReport 和 Trace 中得到相同 token 数。

### Task 3.2：实现结构化预算决策和固定裁剪顺序

**前置依赖：** Task 3.1 已冻结统一估算结果；Task 1.4 Validator 可识别完整 ConversationTurn。

**生产文件：**

- Create: `context/ContextBudgetDecision.java`
- Modify: `context/ContextBudgetManager.java`
- Modify: `context/ContextBudgetReport.java`
- Modify: `context/ContextMessageAssembler.java`
- Modify: `context/ContextAssemblyResult.java`
- Test/Create: `context/ContextBudgetIntegrationTest.java`

**固定裁剪顺序：**

1. 删除 TRACE_ONLY/POLICY_ONLY（本来就不应进入模型）。
2. 删除 OPTIONAL Context Data。
3. 按完整条目裁剪长期记忆，不截断单条文本中间字符。
4. 删除不属于 requiredContextKeys 的低优先级动态 Context Data。
5. 若主要压力来自 SessionMemory，生成一次 compression recommendation。
6. 压缩后仍超限时，从最旧完整 ConversationTurn 开始做仅本次请求可见的历史裁剪。
7. System、当前 UserMessage、required vehicle context 和完整 ToolExchange 不可裁剪。
8. 选中的 ToolSpecification 集合作为原子集合；不能满足预算时明确失败，不随机删除部分工具。

**实施要求：**

- [ ] ContextBudgetDecision 返回最终 messages/tools、每个裁剪动作、压缩建议、estimated/max 和失败原因。
- [ ] ContextPriority 真正参与预算决策，不再只是元数据。
- [ ] ConversationTurn 识别复用严格 Validator 的 ToolExchange 分组语义。
- [ ] ContextBudgetReport 能区分 before/after tokens、trimmed sources、compressionRecommended、withinBudget。
- [ ] 所有裁剪保持原始 List 不变，返回新的不可变列表。

**ContextBudgetDecision 固定字段：**

- `messages`、`toolSpecifications`：本次决策后的不可变最终候选。
- `estimatedBeforeTokens`、`estimatedAfterTokens`、`maxInputTokens`、`withinBudget`。
- `trimActions`：按执行顺序记录 sourceKey、动作类型、before/after tokens，不记录正文。
- `compressionRecommended`、`targetSessionMemoryTokens`。
- `failureCode/failureDetail`：只有无法通过本地裁剪满足预算时设置。

**裁剪实现单位：**

- Context Data 以 Contribution 为单位；长期记忆以 `MemoryEntry` 渲染出的完整条目为单位。
- Session history 以 Validator/identifyTurns 产生的完整 ConversationTurn 为单位。
- ToolSpecification 整体作为一个不可拆分集合；不允许只保留部分 selected/all-fallback tools。
- System/current User/required Context/当前未完成 turn 都是 protected unit。

**测试预算必须使用可注入的小窗口：** 例如 maxInput=200/300，而不是构造数万字符依赖生产 32768。每个测试断言 trimActions 顺序、最终消息结构、估算下降和原输入未修改。

**完成证据：** OPTIONAL、长期记忆、动态 Context、历史 turn 四级裁剪各有独立测试；Tool 集合单独超限时返回明确失败且模型零调用。

### Task 3.3：接入单请求一次生产压缩

**前置依赖：** Task 3.2 能区分“可本地裁剪”与“必须压缩 SessionMemory”。

**生产文件：**

- Modify: `context/ContextOrchestrator.java`
- Modify: `context/ContextAssemblyRequest.java`
- Modify: `context/ContextAssemblyResult.java`
- Modify: `memory/ContextMemoryGateway.java`
- Modify: `memory/MemoryOrchestrator.java`
- Modify: `memory/MemoryCompactionPlan.java`
- Modify: `memory/MemoryCompactionResult.java`
- Modify: `memory/SessionChatMemoryProvider.java`
- Modify: `core/TextAgentLoopOrchestrator.java`
- Test/Create: `context/ContextCompressionIntegrationTest.java`

**生产协议：**

1. assemble 初次读取 SessionMemory Contribution 并执行预算决策。
2. 若报告建议压缩且本请求未尝试，Context 调用 `planSessionCompaction(sessionId, targetTokens)`。
3. Context 把计划交给 Memory 的 `executeCompactionPlan()`；Memory 负责摘要/持久化/cache 失效。
4. 成功后 Context 重新执行 SessionMemory Provider，禁止复用压缩前 Contribution。
5. 重新 assemble 和预算；ContextAssemblyResult 标记 memoryCompacted/reloadRequired。
6. AgentLoop 保存 `persistentCompressionAlreadyAttempted=true`，后续迭代不得再次压缩。
7. 压缩失败时先继续执行可用的 optional/历史裁剪；最终仍超限返回 MEMORY_COMPACTION_FAILED 或 CONTEXT_BUDGET_EXCEEDED。

**实施要求：**

- [ ] MemoryCompactionPlan 生成阶段不调用模型、不写 Store。
- [ ] execute 阶段使用现有 MemoryCompressor，写回成功后失效 SessionChatMemoryProvider cache。
- [ ] 写回失败保持原 Store 和 live cache 可继续读取，不暴露半写状态。
- [ ] TEXT turn 完成后只调用长期记忆提取入口，不再用 tokenEstimate=0 触发旧阈值压缩。
- [ ] legacy SCENE/VL `onTurnComplete()` 行为保持不变。
- [ ] Trace 分别记录 plan、execute、before/after tokens、writeback、reload 和第二次 assemble。

**状态传递契约：**

- `ContextAssemblyRequest.persistentCompressionAlreadyAttempted` 由 TextAgentLoop 的请求局部 boolean 传入，初始 false。
- ContextAssemblyResult 增加/正确填充 `compressionAttempted`、`memoryCompacted`、`chatMemoryReloadRequired` 和压缩诊断；不能继续由 success 工厂固定 false。
- TextAgentLoop 收到 compressionAttempted 后立即把请求局部状态置 true，即使压缩失败也不得在后续 iteration 重试。

**失败处理：**

- plan 不建议压缩：继续本地裁剪。
- execute 失败但本地裁剪后可满足预算：返回成功，同时 Trace 记录 compression failure。
- execute 失败且无法满足预算：返回 MEMORY_COMPACTION_FAILED，模型零调用。
- execute 成功但重读/二次 assemble 失败：返回 CONTEXT_BUILD_FAILED 或具体 Provider error，不使用压缩前快照回退。
- 二次 assemble 仍超限：返回 CONTEXT_BUDGET_EXCEEDED。

**测试 fake 能力：** MemoryGateway fake 必须可配置 plan/no-plan、execute success/failure、重读新快照和写回异常；测试断言 plan/execute 调用次数最多为 1，并检查第二次 assemble 使用新消息对象。

**完成证据：** 正常无需压缩、压缩成功、压缩失败可裁剪、压缩失败不可恢复、压缩后仍超限五类测试通过；Store/cache 一致性由 Memory 测试独立证明。

### Phase 3 验证

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.context.ContextBudgetIntegrationTest" --tests "com.hirain.aiagent.context.ContextCompressionIntegrationTest" --tests "com.hirain.aiagent.memory.*"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

**预期：** 小预算下裁剪顺序稳定；工具集合不被部分删除；压缩最多一次；Store/cache/重读一致；最终超限时模型零调用；全量测试与构建通过。

---

## 7. Phase 4：TEXT/Context 迁移债务清理与最终验收

**阶段目标：** 删除 Context 内已经无调用的 Section/旧预算结构，收紧 TEXT 类型边界，完成 TEXT/Context 文档、Lint、设备和静态所有权验收。IMAGE、VOICE、CONTROL、SCENE、VL 等非 TEXT 遗留路径本阶段冻结保留，后续另立删除计划，不在本阶段迁移、重构或补齐行为测试。

### 7.1 本阶段工作边界

**允许修改：** ContextSection/DebugInfo/Frame 旧字段、ProviderResult 兼容返回、旧字符预算 API、TEXT/Context 文档和 TEXT/Context 测试。

**非 TEXT 冻结边界：**

- `AgentRequest.Type.IMAGE`、`VOICE`、`CONTROL`，以及 SCENE/VL/旧 chat 调用链全部保持现状。
- 不删除、不迁移、不重构这些路径，不调整其 Prompt、PreProcessor、MemoryPolicy、ToolSpecification、Trace 或模型输入。
- 不新增 SCENE/VL characterization test，不为非 TEXT 路径补设备验收矩阵，也不把它们接入 ContextMessageAssembler。
- `CONTROL` 仅视为遗留 Service 命令通道，目前处理 StartListen、StopListen、ClearChatMemory；它不是车辆 Tool 调用，不纳入 Context 全权控制目标。
- 若 Task 4.1/4.2 的删除动作发现非 TEXT 真实引用，必须保留该类型或成员并记录命中位置，禁止通过修改非 TEXT 调用方来完成“零引用”指标。
- 唯一允许的非 TEXT 相关动作是运行 `assembleDebug` 证明项目仍可编译；若 TEXT/Context 清理直接造成编译错误，只允许做保持原行为的最小引用适配，并在 testresult 中逐项列出。

**阶段退出状态：** Context 模块在不影响冻结遗留路径的前提下只保留 TEXT 所需的 Contribution/Prepare/Assembly/Budget 契约；TEXT 不可能从类型上重新获得旧 Prompt/固定工具输入；非 TEXT 代码保持未迁移状态且项目可编译；所有 TEXT/Context 自动化门禁通过。

### Task 4.1：删除 Context 迁移数据结构

**前置依赖：** Phase 3 已证明所有模型输入只依赖 Contributions/BudgetDecision，不再读取旧 Section 字段。

**生产文件：**

- Delete: `context/ContextSection.java`
- Delete: `context/ContextSectionType.java`
- Delete: `context/ContextDebugInfo.java`
- Modify: `context/ContextProvider.java`
- Modify: `context/ContextProviderResult.java`
- Modify: `context/ContextFrame.java`
- Modify: `context/ContextFrameBuilder.java`
- Modify: `context/ContextOrchestrator.java`
- Modify: `context/provider/CallerExtraContextProvider.java`
- Modify: `context/provider/IntentContextProvider.java`
- Modify: `context/provider/LongTermMemoryContextProvider.java`
- Modify: `context/provider/MemoryContextProvider.java`
- Modify: `context/provider/PersonaContextProvider.java`
- Modify: `context/provider/PromptContextProvider.java`
- Modify: `context/provider/RuntimeContextProvider.java`
- Modify: `context/provider/SessionMemoryContextProvider.java`
- Modify: `context/provider/TimeContextProvider.java`
- Modify: `context/provider/ToolGroupContextProvider.java`
- Modify: `context/provider/UserInputContextProvider.java`
- Modify: `context/provider/VehicleStateContextProvider.java`
- Modify: affected Context tests

**实施要求：**

- [ ] ProviderResult 只返回 contributions、outcome、status、errorCode 和 detail。
- [ ] Provider 不再声明 ContextSectionType，不再创建双份 Section/Contribution 数据。
- [ ] ContextFrame 删除 memorySummary、vehicleStateSnapshot、timeContext、promptContext、renderedExtraContext、ContextDebugInfo 和 sections。
- [ ] ContextFrame 只保存请求级身份/选择事实和不可变 request-static contributions。
- [ ] 删除 `fromLegacySection()`；建立名称反映新语义的 success/fallback/failure 工厂。
- [ ] 全仓审计 ContextSection、ContextDebugInfo、renderedExtraContext 引用；TEXT/Context 主路径引用必须为零。若只剩冻结的非 TEXT 真实引用，则保留最小兼容类型/字段并在 testresult 中登记，不修改非 TEXT 调用方。

**删除顺序：**

1. 先把每个 Provider 改为直接构造新 ProviderResult，逐个删除 Section 创建代码。
2. 再从 ContextOrchestrator 删除 sections 聚合。
3. 再从 ContextFrame/Builder 删除旧字段和 builder 方法。
4. 最后删除已确认只服务 TEXT/Context 且全仓无真实引用的 ContextSection、ContextSectionType、ContextDebugInfo 文件和 ProviderResult.fromLegacySection；存在冻结非 TEXT 引用的对象不得删除。

每完成一步都先编译 Context 包测试，禁止一次删除全部类型后用大范围修补解决编译错误。

**ProviderResult 最终契约：** 不可变 contributions 列表、单个 ContextProviderOutcome、status/errorCode/detail；success/fallback/failure 工厂必须显式接收 providerName，不能用 null outcome 表示成功。

**完成证据：** PowerShell `Get-ChildItem | Select-String` 将上述旧类型和字段的每个命中分类为 TEXT/Context、冻结非 TEXT 或测试；TEXT/Context 主路径命中为零，保留的非 TEXT 命中有清单和保留理由；Context 全量测试通过，Provider 数量与删除前一致。

### Task 4.2：删除旧预算 API 和误导性 Trace/测试

**前置依赖：** Task 4.1 已删除旧 Section 字符预算使用者。

**生产文件：**

- Modify: `context/ContextBudgetManager.java`
- Modify: `context/ContextBuildInput.java`
- Modify: Context budget tests
- Modify: `trace/TraceSpanNames.java` 和 recorder tests（仅删除 TEXT/Context 零调用项）

**实施要求：**

- [ ] 删除 sectionCharLimit/memoryCharLimit/toolContextCharLimit/totalCharLimit 和字符串 `trim()/estimateTokens()` 旧主路径。
- [ ] ContextBuildInput 注入结构化 BudgetManager/Estimator/Profile，禁止默认构造隐藏缺失生产依赖。
- [ ] 删除 `AgentLoopContextShadowTest` 等只验证 lambda/常量存在的迁移测试。
- [ ] `PROMPT_ASSEMBLY` 若存在冻结非 TEXT 真实引用则原样保留；只在 TEXT/Context 已有等价 Trace 覆盖且该常量无真实引用时删除，禁止为清理常量修改非 TEXT Trace。

**具体审计：** 删除前分别搜索 `trim(`、`estimateTokens(String`、四个 charLimit getter、AgentLoopContextShadowTest、PROMPT_ASSEMBLY。每个命中必须分类为 TEXT/Context、冻结非 TEXT 或测试；只删除 TEXT/Context 或全仓零调用命中。冻结非 TEXT 命中原样保留，不做调用方适配。

**测试替换规则：** 删除一个迁移测试前，必须指出对应最终行为由哪个测试覆盖。例如 Shadow gateway lambda 测试由 TextAgentLoop ChatRequest 直通测试替代，常量存在测试由生产 parentSpanId 测试替代。

**完成证据：** ContextBudgetManager 只暴露结构化决策 API；测试数量变化在最终 testresult 中逐项解释，不能只报告总数减少。

### Task 4.3：TEXT 身份隔离和外部协议回归

**前置依赖：** Phase 1-3 已完成 TEXT 主链迁移，Task 4.1/4.2 已完成不触碰非 TEXT 的 Context 清理。

**测试文件：**

- Modify: `runtime/AgentRuntimeTest.java`
- Modify: `runtime/RuntimeResponseMapperTest.java`
- Modify/Create: TEXT session/user/persona identity integration tests（优先扩展现有 Context 或 Runtime 测试类，不为同一行为另建重复测试层）
- Verify: AIDL/Parcelable existing tests and `assembleDebug`

**实施要求：**

- [ ] 验证 TEXT session 切换不携带旧 session；user 切换保留 session 对话但切换长期记忆；persona 切换只改变 System Prompt。
- [ ] 验证 AIDL 字段、status/errorType/clientMessageId/sessionId 映射不变。
- [ ] 验证 success、cancel、budget exceeded、required Provider failure 四类结果均保留正确的请求身份和 ContextErrorCode 映射。
- [ ] 非 TEXT 不新增测试、不改测试期望；仅运行全量 JVM 测试和 `assembleDebug` 发现意外编译/既有测试回归。失败时先确认是否由 Phase 4 的 TEXT/Context 清理直接引起，不顺手修复遗留非 TEXT 缺陷。
- [ ] 不具备 JVM 条件的 TEXT SQLite 隔离行为转入设备验收清单，不用无 `@Test` 的空类伪装覆盖。

**TEXT 身份测试：**

- session A -> session B：B 的 ChatRequest 不包含 A 的 User/Ai/Tool 历史。
- 同一 session user A -> user B：短期历史保留，LongTermMemory Contribution 只来自 B。
- 同一 session persona chat -> friendly：历史保留，System Prompt 切换且只有一个。

**外部协议断言：** requestId、sessionId、userId、personaId、clientMessageId、status、errorType 在 success/cancel/budget/provider failure 中均保持正确，不新增 Parcelable 字段。

**完成证据：** TEXT 身份矩阵、ContextErrorCode/RuntimeResponseMapper 和既有 AIDL 测试全部通过；`assembleDebug` 通过；没有新增或修改 SCENE/VL/IMAGE/VOICE/CONTROL 行为测试；TEXT 设备项单独列为 executed/not-executed。

### Task 4.4：文档、静态所有权与最终门禁

**前置依赖：** Task 4.1-4.3 生产代码和自动化回归均完成。

**文档文件：**

- Modify: `docs/overview/context-module-overview.md`
- Modify: `docs/overview/aiaagent-langchain4j-boundary-and-completion-overview.md`
- Create: `docs/testresult/2026-07-11-context-full-control-final-testresult.md`
- Update: final act summary only after verification succeeds

**静态审计要求：**

- [ ] TEXT Service 只创建 `TextAgentLoopOrchestrator`。
- [ ] TextAgentLoop 不 import PromptManager/SystemMessage/Context/固定 ToolSpecification。
- [ ] TEXT ChatRequest 的 messages/tools 只来自 ContextAssemblyResult。
- [ ] ContextMessageAssembler 不接收独立 sessionMessages，不读取数据库/车辆/时间/Trace。
- [ ] ContextSection、ContextDebugInfo、ContextMode、ContextBuildResult、ContextExtraPreProcessor 在 TEXT/Context 主路径引用为零；冻结非 TEXT 的保留命中有清单。
- [ ] Context Trace 包含 prepare/assemble；冻结非 TEXT Trace 不作为本阶段验收对象，也不为追求统一而修改。
- [ ] `lint.xml` 没有新增忽略项；现有三项系统应用豁免在 testresult 中说明。

**最终 testresult 必须记录：** 执行日期、Git worktree/commit 状态、完整命令、Gradle exit code、suite/test/failure/error/skipped 数量、Lint error/warning 数量、APK 输出位置、TEXT 设备场景逐项结果、未执行原因、冻结非 TEXT 保留项和仍存风险。

**最终文档一致性：** overview 中的调用链、Provider 表、预算顺序、压缩协议、Trace 层级和完成度必须与生产代码一致；act_summary 不得继续写“13/15 已修复”或引用旧 205 测试数字，必须使用最终重跑结果。

**完成证据：** 静态审计全部为预期命中，三条 Gradle 命令使用 `--rerun-tasks` 新鲜通过，最终 testresult 和 overview 已由执行者逐项自检无占位符/矛盾。

### Phase 4 最终验证

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat assembleDebug --rerun-tasks
.\gradlew.bat lintDebug --rerun-tasks
```

**设备/集成验证：**

- 连续普通 TEXT 对话至少 10 轮，确认无 Agent busy 和重复 UserMessage。
- 新建并切换 session，确认短期历史隔离。
- 同一 session 切换 user，确认短期对话保留、长期记忆切换。
- chat/friendly/concise 切换，确认 System Prompt 不串扰。
- 明确车控、模糊车控、CHAT_ONLY，核对最终工具集合和 SafetyGuard。
- 多工具调用中途取消，确认未执行工具有 cancelled result，下一请求消息合法。
- 构造超预算长会话，确认裁剪/一次压缩/重读/二次 assemble Trace 完整。
- Phoenix 核对 `agent.request -> agent.loop -> context.prepare/context.assemble/gen_ai.chat/tool.execute/memory.*` 父子关系。

**最终预期：** 所有命令成功；TEXT 设备未执行项必须明确标记而不是推定通过；文档、总结和代码一致；IMAGE/VOICE/CONTROL/SCENE/VL 明确记录为冻结遗留范围而非 Context 已接管范围。满足后可将“TEXT Context 全权控制”标记为完成，不能表述为所有输入类型已经统一接管。

---

## 8. 问题覆盖矩阵

| 问题 | 处理位置 |
|---|---|
| 三个原 P0 缺少真实回归保护 | Phase 1 Task 1.1 |
| required Prompt/Tool/Session/Vehicle 可绕过 | Phase 1 Task 1.2 |
| 取消迟到 AiMessage、assemble 取消映射错误 | Phase 1 Task 1.3 |
| 多工具取消留下不完整 ToolExchange | Phase 1 Task 1.3、1.4 |
| Tool Call ID/数量/重复结果校验不足 | Phase 1 Task 1.4 |
| AgentLoopState 非原子抢占 | Phase 1 Task 1.5 |
| SessionMemory Provider 不是消息来源 | Phase 2 Task 2.1 |
| TEXT 仍使用旧 Prompt/固定工具构造器 | Phase 2 Task 2.2 |
| ContextErrorCode 在 Runtime 丢失 | Phase 1 Task 1.3 |
| ContextTraceRecorder 未接线、outcomes 丢失 | Phase 2 Task 2.3 |
| Tool parameter schema 未计入预算 | Phase 3 Task 3.1 |
| 无优先级裁剪 | Phase 3 Task 3.2 |
| 生产压缩/reload/reassemble 未接入 | Phase 3 Task 3.3 |
| tokenEstimate=0 和旧 turn 压缩职责混用 | Phase 3 Task 3.3 |
| ContextSection/DebugInfo/Frame 旧字段 | Phase 4 Task 4.1 |
| 旧字符预算 API、影子测试和误导性常量测试 | Phase 4 Task 4.2 |
| TEXT 身份隔离、外部协议、设备和最终文档覆盖不足 | Phase 4 Task 4.3、4.4 |
| IMAGE/VOICE/CONTROL/SCENE/VL 遗留路径 | Phase 4 明确冻结保留，后续独立删除计划处理 |

---

## 9. 实施顺序与停止条件

- 必须按 Phase 1 -> 2 -> 3 -> 4 顺序执行，不能先做大规模删除再补正确性测试。
- 每个 Phase 的目标测试、全量 JVM 测试和 assembleDebug 通过后才能进入下一阶段。
- Phase 4 若需要修改 IMAGE/VOICE/CONTROL/SCENE/VL 的实现或测试才能继续，应立即停止并报告；不得把非 TEXT 迁移或清理塞入本计划。
- 任一阶段出现 AIDL 协议变化、Tool Schema 部分裁剪或取消后继续执行工具，应立即停止并回到对应 Phase 修正。
- Phase 3 压缩写回测试没有证明 Store/cache/重读一致前，不得在 Service 生产装配中启用。
- Phase 4 最终总结只能引用本轮重新执行的测试结果，不能复用历史 BUILD SUCCESSFUL。
