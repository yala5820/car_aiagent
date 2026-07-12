# Context 全权控制整改复验报告

**复验日期：** 2026-07-11  
**复验依据：** `2026-07-11-context-full-control-implementation-acceptance-review.md`  
**整改总结：** `docs/act_summary/2026-07-11-context-full-control-15-issues-fix-summary.md`  
**复验范围：** 上一轮 3 P0 + 8 P1 + 4 P2、完整 TEXT 生产链、Provider/Assembler/Budget/Memory/Trace/取消/测试边界  
**最终结论：** **整改有实质进展，但仍不通过 Context Phase 1-6 最终验收。**

---

## 一、执行结论

上一轮三个 P0 在生产代码中均能找到对应修改：

1. `AIAgentService` 已向 `ContextBuildInput` 注入真实 `ToolRegistry`。
2. 新 TEXT AgentLoop 的成功和参数错误路径已补充状态终结。
3. 新 TEXT 工具路径已恢复 `SafetyGuard` 审查。

空输入校验、LLM/Tool 显式 Trace parent 以及 Lint 配置也已落地。本轮强制重跑得到：

```text
testDebugUnitTest: 42 suites, 205 tests, 0 failures, 0 errors, 0 skipped
assembleDebug: BUILD SUCCESSFUL
lintDebug: BUILD SUCCESSFUL（0 errors，35 warnings）
```

但是整改总结所称“15 项中 13 项已修复”不成立。总结自身已经承认生产压缩、预算裁剪、Tool Call ID 校验、ContextSection 清理和非 TEXT 回归没有完成；进一步代码审查还发现 required 语义、SessionMemory 消息来源、取消原子性、错误映射和真实主链测试仍有漏洞。

当前状态应定义为：**P0 代码补丁已落地，但完整计划仍处于未闭环集成状态。**

---

## 二、高优先级问题

### P1-1：计划要求的预算裁剪和生产压缩仍未实现

**证据：**

- `ContextMessageAssembler.java:127-130` 只计算 estimated/max/withinBudget，没有执行任何优先级裁剪。
- `ContextBudgetManager.java:85-86` 仍明确写着“只估算不实际裁剪”，而且生产装配没有调用 `generateBudgetReport()`。
- `ContextMessageAssembler` 仍使用自己的静态估算函数，没有使用已定义的 `HeuristicContextTokenEstimator` 和 `ContextBuildInput.budgetManager()`。
- `HeuristicContextTokenEstimator.java:47` 仍为 TODO，没有统计 Tool parameter schema；Assembler 的估算同样只计算工具名称和描述。
- `MemoryOrchestrator.planSessionCompaction()` / `executeCompactionPlan()` 没有生产调用者。
- `ContextAssemblyResult.success():57-64` 仍固定返回 `memoryCompacted=false`、`chatMemoryReloadRequired=false`。
- `AgentLoopOrchestrator.java:600-602、636-638` 仍向 `onTurnComplete()` 传 tokenEstimate=0。

**影响：**

- 超限时只能直接失败，计划中的 OPTIONAL -> 长期记忆 -> 工具 -> 一次压缩 -> 最旧完整历史单元裁剪链不存在。
- allToolsFallback 的完整 Tool Schema 可能被严重低估，实际请求仍可能超过模型窗口。
- 长会话不会在 Context 独占链执行计划要求的一次压缩、缓存失效、重读和二次 assemble。

**结论：** 上一轮 P1-1 只能算“预算上限接入”，P1-2 未修复；Phase 4 和 Phase 5 的关键退出条件仍不满足。

---

### P1-2：required Provider 语义仍可被绕过

**证据：**

- 全部 Provider 中只有 `PromptContextProvider` 和 `UserInputContextProvider` 覆盖 `required()`。
- `PromptContextProvider.java:68-71` 在 PromptManager 缺失时把 required Provider 标为 FALLBACK。
- `ContextProviderResult.java:65-67` 把 FALLBACK 定义为 `success=true`，因此 `ContextOrchestrator.java:118-127` 的 required 失败判断不会触发。
- 结果是系统可以带一个空 `SystemMessage` 继续调用模型，这与 Prompt“始终 required”的计划规则冲突。
- `ToolGroupContextProvider` 没有 required 覆盖。明确工具名解析失败时虽然返回 FAILED，但 Orchestrator 会把它当 optional 继续执行。
- `SessionMemoryContextProvider` 注释声明 persistent TEXT 中 required，却没有覆盖 `required()`；读取异常被转换为 FALLBACK。
- `VehicleStateContextProvider` 没有根据 ToolGroup 的 `requiredContextKeys` 动态升级 required。即使门锁、底盘组要求 `vehicle_status`，车辆状态读取失败仍继续调用模型。
- `ContextOrchestrator.assemble():222-234` 对动态 Provider 只收集失败，不检查 required，也没有失败返回。

**影响：** 无 Prompt、无工具、无 SessionMemory 或高风险车控缺失车辆状态时，模型仍可能继续执行。required 目前只是部分标签，不是可靠门禁。

---

### P1-3：SessionMemory Provider 仍不是最终消息来源

**证据：**

- `SessionMemoryContextProvider.java:56-73` 每轮读取 `MemorySnapshot` 并输出 `SOURCE_SESSION_MEMORY` 的 Message Contribution。
- `ContextMessageAssembler.java:49-114` 只消费 Tool/Text Contribution，从未消费任何 Message Contribution。
- Assembler 最终直接追加 `AgentLoopOrchestrator` 通过 `ContextAssemblyRequest.sessionMessages` 传入的列表。
- 动态 Provider 读取结果因此被执行但被丢弃，增加一次无效读取，并可能与真实装配快照不一致。
- `AgentLoopOrchestrator.java:440-445` 虽优先使用 Provider 创建的 currentUserMessage，但仍保留直接重新构造 UserMessage 的 fallback，类型边界仍允许绕过 UserInput Provider。

**影响：** Context 已成为最终列表的返回者，但 SessionMemory Provider 仍不是实际数据源；Provider 的 required、裁剪、压缩重读和诊断无法控制真实历史消息。

**结论：** 上一轮 P1-4 仅修复了 currentUserMessage 的一部分，未完成 SessionMemory 所有权迁移。

---

### P1-4：取消仍会产生迟到 AiMessage，且 assemble 取消被映射成错误类型

**证据：**

- AgentLoop 在 `AgentLoopOrchestrator.java:494-498` 调模型前检查取消。
- 如果取消发生在模型调用期间，模型返回后代码会先在 `AgentLoopOrchestrator.java:525` 把 AiMessage 写入 ChatMemory。
- 下一次取消检查位于工具分支的 530-534 行或文本分支的 623-627 行，已经晚于持久化写入。
- `ContextOrchestrator.java:208-214` 在 assemble 开始时返回 `CONTEXT_CANCELLED` failure。
- `AgentLoopOrchestrator.java:473-478` 对所有 assemble failure 一律映射为 `CONTEXT_BUILD_FAILED`，不会映射为 `CANCELLED`。

**影响：**

- 已取消请求仍会污染后续会话历史，与计划“取消后不产生迟到写入”冲突。
- 同一个取消事实会因发生时机不同，对外表现为 CANCELLED 或 CONTEXT_BUILD_FAILED。

---

### P1-5：多工具调用中途取消会留下不完整 ToolExchange

**证据：**

- AgentLoop 会先把包含全部 ToolExecutionRequest 的 AiMessage 写入 ChatMemory。
- 每个工具前检查取消；若第一个工具已经完成、第二个工具前检测到取消，代码在 `AgentLoopOrchestrator.java:543-547` break，并于 585-589 行返回 CANCELLED。
- 此时第一个 ToolResult 已持久化，第二个 ToolExecutionRequest 没有对应结果。
- `ContextMessageSequenceValidator` 只累计历史工具名称，不按 request id 和当前 pending 集合匹配，也不检查每个请求是否都有且只有一个结果。
- 下一请求可能把这个未闭合 ToolExchange 再次发送给模型。

**影响：** 取消路径可以生成 LangChain4j/OpenAI Tool Calling 不完整消息序列，导致下一次请求被模型接口拒绝或错误关联工具结果。

---

### P1-6：消息序列校验仍不满足原子性和唯一 System 约束

**证据：**

- `ContextMessageSequenceValidator.java:47-69` 只按历史 toolName 匹配，不按 tool request id 匹配，不清理 pending 集合，也不检查缺失或重复 ToolResult。
- 同名工具在不同轮次出现时，错误 ID 的 ToolResult 仍会通过。
- `ContextMessageAssembler.java:73-89` 对重复 System Contribution 只保留第一条并记录 warning，冲突内容不会导致失败。
- `ContextMessageAssemblerTest.noSystemMessage_assemblesWithoutSystem()` 仍把“没有 SystemMessage 也成功”定义为预期行为。
- Prompt Provider required 语义又允许生成空 SystemMessage。

**影响：** Validator 不能承担计划定义的最终消息合法性门禁。整改总结已把该问题标为延期，不能计入“已修复”。

---

### P1-7：Context 失败码在 Runtime 边界继续丢失

**证据：**

- `ContextOrchestrator.prepare()` 已能返回 `REQUIRED_PROVIDER_FAILED` 等领域错误码。
- `AgentRuntime.failureFromPrepare():241-245` 仍把所有 prepare failure 包装成普通 RuntimeException，再生成 `errorType=EXCEPTION`。
- assemble failure 也在 AgentLoop 统一变成 `CONTEXT_BUILD_FAILED`，没有按 `ContextErrorCode` 映射取消、工具解析失败和压缩失败。
- `RuntimeResponseMapper` 虽已具备 `CONTEXT_BUILD_FAILED`、`CONTEXT_BUDGET_EXCEEDED`、`CANCELLED` 分支，但上游并未稳定产生这些类型。

**影响：** 调用方和 Trace 无法区分 required Provider、工具解析、取消及 Context 内部异常，错误码体系仍是半接线状态。

---

### P1-8：Phase 6 最终类型约束和迁移清理仍未完成

**证据：**

- `AIAgentService.kt:390-397` TEXT 生产装配仍调用被 `@Deprecated` 标记的旧构造器，并继续传 PromptManager 和固定 `toolRegistry.toolSpecifications`。
- `AgentLoopOrchestrator.java:75-115` 仍保存 PromptManager、fallbackChatMemory、effectiveToolSpecs 和旧构造依赖，没有计划要求的 TEXT 专用窄构造器。
- `ContextFrame` 仍保存 memorySummary、vehicleStateSnapshot、timeContext、promptContext、renderedExtraContext、ContextDebugInfo 和 sections 等旧字段。
- 11 个 Provider 仍创建 ContextSection，`ContextProviderResult` 仍同时返回 Section 与 Contribution。
- `ContextBudgetManager` 仍保留计划要求 Phase 6 删除的字符级 API。
- `buildSystemPromptMessage()`、`PROMPT_ASSEMBLY` 和旧 AgentLoop 输入路径仍存在。

**影响：** 目前只是删除了 ContextMode、ContextBuildResult 和 ContextExtraPreProcessor。类型系统没有阻止 TEXT 后续重新使用旧 Prompt/固定工具输入所有权，Phase 6 退出条件不成立。

---

## 三、测试与可观测性问题

### P2-1：新增“TEXT 生产路径测试”没有执行 AgentLoopOrchestrator

`AgentLoopOrchestratorTextPathTest.textRuntime_prepareAndExecuteSucceeds()` 在 31-39 行向 AgentRuntime 注入的是一个直接返回 success 的 lambda。测试没有：

- 构造 `AgentLoopOrchestrator`；
- 调用新 TEXT execute；
- 构造真实 `ChatRequest`；
- 验证 ToolRegistry 注入；
- 验证连续两次请求释放 RUNNING；
- 验证 SafetyGuard 否决；
- 验证预算、取消、ToolResult 和 ResultCollector。

全仓测试搜索也没有其他 `new AgentLoopOrchestrator(...)` 或上述行为断言。因此三个 P0 目前只有静态代码证据，没有自动化回归保护。整改总结第 181-183 行把该测试称为“完整 TEXT 生产路径”是不准确的。

### P2-2：Context Trace 只完成父节点修正，专属诊断仍未接线

- AgentLoop 已显式把 LLM/Tool span parent 指向当前 agent.loop，这项静态修改成立。
- 现有 `TraceSessionParentChildTest` 只测试 TraceSession 通用 API，没有执行 Runtime -> Context -> LLM/Tool 生产链。
- `ContextTraceRecorder` 在生产代码中没有实例化调用者。
- ContextOrchestrator 直接使用 GlobalOpenTelemetry，只写 provider/message/tool 数量，没有写 required/optional outcome、预算、裁剪、压缩和 fallback 明细。
- assemble 收集的动态 Provider outcomes 没有合并进 `ContextAssemblyResult.providerOutcomes()`；Assembler 自己创建并返回的是空 outcomes。

因此 Trace 父子代码方向正确，但完整 Context 可观测性仍未达到计划标准。

### P2-3：AgentLoopState 的 tryStart 不是原子抢占

`AgentLoopState.tryStart():25-33` 使用 `get() -> 判断 -> set()`，不是 `compareAndSet()`。类注释声称线程安全，但两个并发调用可能同时看到非 RUNNING 并同时进入循环。

当前 Service 的单 HandlerThread 降低了主入口触发概率，但该对象本身的并发契约不成立；若未来 Runtime 多执行器或测试并行调用，会导致共享状态、工具和模型请求同时运行。

### P2-4：Lint 通过依赖项目级忽略规则，应记录为基线策略

`app/lint.xml` 全局忽略了三类系统应用权限问题。本次 Lint 实际为 0 errors、35 warnings，构建门禁已通过；但这属于“确认并豁免既有系统应用规则”，不是消除了原 98 个问题。最终 testresult 应记录忽略原因和适用边界，避免后续新增同类权限问题被无条件隐藏。

---

## 四、上一轮 15 项复验矩阵

| 原问题 | 复验结果 | 说明 |
|---|---|---|
| P0-1 ToolRegistry 未注入 | 代码已修复，缺回归测试 | Service 已注入；没有真实 ChatRequest 工具集合测试。 |
| P0-2 RUNNING 不释放 | 代码已修复，缺回归测试 | 终态已补；没有连续两次真实 execute 测试。 |
| P0-3 SafetyGuard 绕过 | 代码已修复，缺回归测试 | 安全链已恢复；没有高速开门零执行测试。 |
| P1-1 预算未接入 | 部分修复 | 仅估算和超限阻断，无裁剪、Estimator/schema 完整接入。 |
| P1-2 生产压缩 | 未修复 | 总结明确延期，生产无 plan/execute/reload/reassemble。 |
| P1-3 required 语义 | 部分修复 | 只覆盖 Prompt/User；Prompt fallback、Tool/Session/Vehicle 仍绕过。 |
| P1-4 Provider 消息来源 | 部分修复 | currentUser 已提取；SessionMemory Contribution 仍未消费。 |
| P1-5 取消覆盖 | 部分修复 | 增加检查点，但存在迟到 AiMessage、不完整 ToolExchange 和错误映射。 |
| P1-6 Loop 契约/错误 | 部分修复 | Safety/Terminator/Collector 恢复；Context 领域错误仍丢失。 |
| P1-7 消息校验 | 未完成 | 仅加 toolName，缺 request id、数量、pending 原子性。 |
| P1-8 Trace 层级 | 部分修复 | parent 代码已修；无生产链测试，Context 专属诊断未接线。 |
| P2-1 Phase 6 清理 | 部分修复 | 删除三项，旧 Section/构造器/字段/Prompt span 仍在。 |
| P2-2 空输入 | 已修复 | Runtime 在 Context 前返回 INVALID_INPUT。 |
| P2-3 主链回归测试 | 未修复 | 新测试实际只验证 Runtime 调用 lambda。 |
| P2-4 Lint | 已建立门禁 | 强制重跑通过；依赖 lint.xml 系统应用规则豁免。 |

---

## 五、复验结论与整改顺序

### 验收结论

**本轮不通过最终验收。** 可以确认下属已经修复三个 P0 的生产代码位置，但不能确认“15 项完成”，更不能确认 Context Phase 1-6 已完成。

### 下一轮最低整改顺序

1. 先补真实 AgentLoop 测试，锁定三个 P0、连续请求、SafetyGuard、Tool 集合和 ChatRequest 直通契约。
2. 修正 required 规则：Prompt 不允许空 fallback；Tool 明确解析失败、persistent SessionMemory、required vehicle_status 必须中止。
3. 让 SessionMemory Message Contribution 成为 Assembler 的真实历史来源，删除 AgentLoop 的 UserMessage fallback。
4. 修复取消原子性：模型返回后、写 AiMessage 前再次检查；多工具取消时保证每个已持久化 Tool Call 都有结果或采用可恢复状态。
5. 按 request id 实现严格 ToolExchange Validator，并把重复/缺失 System 作为 required failure。
6. 完整接入预算裁剪、一次生产压缩、cache 失效、重读和二次 assemble。
7. 保留具体 ContextErrorCode 到 Runtime/Response，并接通 ContextTraceRecorder 的预算、Provider、裁剪和压缩诊断。
8. 最后完成 Phase 6 类型清理与非 TEXT、设备、多会话/多用户人工回归。

在以上问题关闭前，当前版本只能进入下一轮整改，不能标记为 Context 全权控制改造完成。
