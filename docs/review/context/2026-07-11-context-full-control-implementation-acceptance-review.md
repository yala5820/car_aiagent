# Context 全权控制改造 Phase 1-6 验收审查报告

**审查日期：** 2026-07-11  
**审查对象：** `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md` Phase 1-6 及其实现  
**下属总结：** `docs/act_summary/context-module-full-control-summary.md`  
**审查方式：** 计划逐项核对、生产调用链静态审查、测试覆盖审查、强制重新执行 Gradle 验证  
**验收结论：** **不通过，禁止按“Phase 1-6 已完成”合入或交付。**

---

## 一、结论摘要

当前代码已经建立 Contribution、Provider、prepare/assemble、ContextAssemblyResult 等基础结构，也已将 TEXT `ChatRequest` 的表面构造入口切换到 `ContextAssemblyResult`。但是主生产链存在三个直接阻断问题：

1. AIAgentService 未向 Context 注入真实 `ToolRegistry`，生产 TEXT 请求得到的工具规格始终为空，车控 Tool Calling 实际不可用。
2. 新 TEXT AgentLoop 在成功返回时没有调用 `state.markCompleted()`，共享 Orchestrator 完成第一次请求后仍保持 `RUNNING`，后续请求将返回 `Agent is busy`。
3. 新 TEXT AgentLoop 直接执行工具，完全绕过已经配置的 `SafetyGuard`，车速门锁等既有安全策略失效。

此外，计划中的预算裁剪、生产压缩、required Provider 失败中止、取消检查、真实 Trace 层级和 Phase 6 清理均未完成。下属总结文档第 329-342 行其实也明确承认预算、压缩、空输入、Trace 和非 TEXT 回归未完成，因此该文档自身已经证明 Phase 1-6 不满足计划退出条件。

本次验收不能把“代码可编译、部分单元测试通过”解释为“Context 全权控制改造完成”。按照计划定义，当前最多属于**主链提前切换后的未完成集成状态**，不具备完整功能和安全验收条件。

---

## 二、阻断问题

### P0-1：生产 Context 未注入 ToolRegistry，TEXT 模型看不到任何工具

**证据：**

- `AIAgentService.kt:378-385` 构造 `ContextBuildInput` 时只注入了 `ToolGroupRegistry`、Prompt、Memory、Vehicle 和 Time，没有调用 `.toolRegistry(toolRegistry)`。
- `ToolGroupContextProvider.java:78-89` 只有在 `input.toolRegistry()` 非空时才解析全部或选中工具；为空时直接返回空列表，并仍维持 SUCCESS。
- `ContextMessageAssembler.java:48-69` 最终工具集合完全来自 `ToolContextContribution`。
- `AgentLoopOrchestrator.java:475-478` 将该空集合原样放入真实 `ChatRequest`。

**影响：**

- 明确车控、模糊车控和 `allToolsFallback=true` 均不会向模型暴露工具。
- ToolGroupSelector 和 Context 工具 Provider 虽然运行，但生产能力被静默降级为纯聊天。
- 现有 Provider 单测通过不能证明 Service 生产装配正确。

**计划违背：** Phase 5 Task 5.3 要求 Service 装配真实 ToolRegistry；Phase 5 回归要求明确车控和模糊车控均暴露正确工具集合。

---

### P0-2：新 TEXT AgentLoop 成功后不结束 RUNNING 状态，第二次请求会被永久拒绝

**证据：**

- `AgentLoopOrchestrator.java:404-407` 一进入新 TEXT 方法就执行 `state.tryStart()`。
- `AgentLoopOrchestrator.java:541-543` 成功路径直接返回 `AgentResult.success(...)`，此前没有 `state.markCompleted()`。
- `AgentLoopOrchestrator.java:408-419` 多个参数校验失败分支也在 `tryStart()` 之后直接返回，没有释放 RUNNING 状态。
- 旧执行路径在 `AgentLoopOrchestrator.java:309、344、351` 明确调用 `state.markCompleted()`，新路径漏掉了同等处理。
- `AIAgentService.kt:388-403` 整个 TEXT 生产链复用同一个 `textOrchestrator` 实例，不是每个请求新建实例。

**影响：**

- 第一次成功对话后，后续所有 TEXT 请求都会命中 `Agent is busy`。
- prepare 结果非法、sessionId 缺失等前置错误也会把实例永久卡在 RUNNING。
- 会话切换、用户切换、多轮连续请求均无法可靠工作。

**测试漏洞：** 全仓测试没有真实构造并调用 `AgentLoopOrchestrator.execute(RequestSession, ContextPrepareResult)`；`AgentLoopContextShadowTest` 只直接调用了一个 Gateway lambda，无法发现状态泄漏。

---

### P0-3：新 TEXT 工具执行绕过 SafetyGuard，既有车控安全策略失效

**证据：**

- `AgentConfigFactory.java:154-161` TEXT Persona 仍配置 `SpeedBasedDoorLockGuard` 和 `SafetyVetoTerminator`。
- 旧链路在 `AgentLoopOrchestrator.java:261-279` 逐个执行 `config.safetyGuards()`，被否决时不会调用工具。
- 新链路在 `AgentLoopOrchestrator.java:500-523` 收到 Tool Call 后直接调用 `config.toolExecutor().execute(req)`，没有任何 SafetyGuard 审查。

**影响：**

- 高速状态下禁止开门等既有安全约束不再生效。
- 后续新增的安全模块不能作为当前绕过既有安全链的理由；计划明确要求 Context 只接管输入，SafetyGuard 仍由 AgentLoop 保持原职责和顺序。
- 这是车控系统的安全回归，不能以 Demo 阶段为由降级验收。

---

## 三、高优先级问题

### P1-1：预算策略没有进入真实装配，ContextBudgetReport 实际为无限预算

**证据：**

- `AgentLoopOrchestrator.java:427、452-455` 虽创建并传入 `ContextBudgetPolicy`，但后续没有消费结果。
- `ContextOrchestrator.java:200-205` 调用 Assembler 时只传 frame 和 sessionMessages，完全忽略 request 中的 budgetPolicy。
- `ContextMessageAssembler.java:118-123` 自行估算后把 `maxTokens` 固定成 `Integer.MAX_VALUE`，并固定 `withinBudget=true`。
- `ContextBudgetManager.java:71-87` 的结构化 API 注释和实现都表明只估算、不裁剪；该方法也没有生产调用者。
- 工具估算只统计名称和描述，`ContextMessageAssembler.java:139-146` 没有计算参数 JSON Schema。

**影响：** Context 永远不会返回 `CONTEXT_BUDGET_EXCEEDED`，不会按优先级裁剪，也不能阻止超窗口请求进入模型。Phase 4 和 Phase 5 的核心目标没有实现。

---

### P1-2：生产压缩、写回和 ChatMemory 重载协议完全未接入

**证据：**

- `MemoryOrchestrator.java:336、354` 虽存在 `planSessionCompaction()` 和 `executeCompactionPlan()`，生产 Context/AgentLoop 没有调用。
- `ContextAssemblyResult.java:57-64` 所有成功结果都把 `memoryCompacted` 和 `chatMemoryReloadRequired` 固定为 false。
- `AgentLoopOrchestrator.java:452-455` 每轮都把 `persistentCompressionAlreadyAttempted` 固定传 false，之后也不读取压缩或重载标志。
- `AgentLoopOrchestrator.java:536-538` turn 完成时传入的 tokenEstimate 固定为 0，不能代表真实上下文压力。

**影响：** 计划中“一次压缩、原子写回、失效 live cache、重新读取并再次 assemble”的生产协议不存在。长会话只能继续膨胀，并与未接入预算问题叠加。

---

### P1-3：Provider 的 required 语义未执行，关键来源失败后仍会调用模型

**证据：**

- `ContextProvider.java:27-34` 明确约定 required Provider 失败时 prepare/assemble 应失败，但所有 Provider 都没有覆盖 `required()`。
- Prompt、UserInput、Tool 等 Contribution 自身虽设置 required，但 `ContextOrchestrator.java:110-153` 只收集 outcome，不检查 FAILED/errorCode/required，最终无条件返回 SUCCESS。
- 动态 Provider 在 `ContextOrchestrator.java:179-213` 失败后同样只记录 outcome，继续装配。
- `PromptContextProvider.java:55-85` PromptManager 缺失或渲染异常时会返回 FAILED 和空 Prompt Contribution，但 Orchestrator 不会中止。
- `ToolGroupContextProvider.java:97-115` 工具名解析失败也不会阻止后续模型调用。

**影响：** 模型可能在无系统 Prompt、缺工具或关键记忆读取失败的情况下继续执行；错误码体系只有类型，没有真实控制效果。

---

### P1-4：Current User 和 Session Memory Provider 不是最终消息的真实来源

**证据：**

- `UserInputContextProvider.java:51-60` 创建了唯一 CURRENT_USER `MessageContextContribution`。
- `ContextOrchestrator.java:153` 却把 `ContextPrepareResult.currentUserMessage` 明确返回为 null。
- `AgentLoopOrchestrator.java:431-435` 再次自行格式化和创建 UserMessage，违背 prepare 契约。
- `SessionMemoryContextProvider.java:56-73` 读取并输出 Message Contribution。
- `ContextMessageAssembler.java:48-105` 只消费 Tool/Text Contribution，完全不消费 Message Contribution，而是直接追加 AgentLoop 传入的 `sessionMessages`。

**影响：** Provider 虽然被调用，但其输出只是观测数据；AgentLoop 仍决定当前用户消息与历史消息来源。Context 没有达到计划定义的真实输入所有权，后续对 Provider 做裁剪、失败控制或压缩也无法作用于最终消息。

---

### P1-5：取消检查未覆盖 assemble、压缩和模型调用前边界

**证据：**

- `ContextOrchestrator.prepare()` 只在全部 Provider 执行前后检查取消，没有在各 required Provider 之间检查。
- `ContextOrchestrator.assemble():165-216` 完全不读取 `request.cancelChecker()`。
- `AgentLoopOrchestrator.java:448-478` 组装返回后直接构建请求，没有模型调用前的最后取消检查。

**影响：** 用户在动态 Provider 读取期间或 assemble 完成后取消，请求仍可能调用模型；工具迭代也缺少工具执行前的取消门禁。计划要求的“取消后不产生迟到模型/工具/记忆写入”没有成立。

---

### P1-6：新 TEXT 循环破坏 AgentLoop 组件契约和错误语义

**证据：**

- 新路径从未调用 `config.terminator()` 和 `config.resultCollector()`，而是直接返回 `AgentResult.success`。
- 所有 assemble 失败都在 `AgentLoopOrchestrator.java:463-468` 映射为 `CONTEXT_BUILD_FAILED`，丢失预算超限、取消、压缩失败等具体语义。
- 整个方法的异常在 `AgentLoopOrchestrator.java:549-552` 一律映射为 `MODEL_CALL_FAILED`，工具异常也不会成为 `TOOL_EXECUTION_FAILED`。
- ToolExecutor 抛异常时，Ai tool request 已写入 ChatMemory，但对应 ToolResult 没有写入，留下半个 ToolExchange。
- `AgentRuntime.java:230-234` 又把 prepare 失败包装为普通 RuntimeException，继续丢失 ContextErrorCode。

**影响：** 终止策略、结果收集、响应映射和工具消息原子性均出现行为回归，外部调用方无法得到稳定且准确的失败分类。

---

### P1-7：消息序列校验不足以保证 LangChain4j Tool Calling 原子性

**证据：**

- `ContextMessageAssembler.java:71-83` 只取第一个 System Contribution，重复 System 在进入 Validator 前已被静默丢弃，无法发现冲突。
- Assembler 和 Validator 都允许完全没有 SystemMessage。
- `ContextMessageSequenceValidator.java:46-57` 只使用一个 `foundToolCall` 布尔值；不按 tool request id/name 匹配结果，不检查结果数量，也不检查未闭合的 tool request。

**影响：** 错误 ToolResult 可能错误匹配任意历史 Tool Call，未完成 ToolExchange 也可通过校验并进入下一次模型调用，存在 API 请求被模型服务拒绝的风险。

---

### P1-8：Trace 层级仍不符合设计，Context Trace 也未统一使用 Recorder

**证据：**

- `AgentRuntime.java:191-226` 创建并激活 `agent.loop` span。
- 新 AgentLoop 在 `AgentLoopOrchestrator.java:480-504` 调用无 parent 参数的 `startLlmCall()` / `startTool()`。
- `AgentTraceRecorder.java:67-77、119-127` 的无 parent 重载传 null。
- `TraceSession.java:90-96` 在 parent 为 null 时回退到 request rootContext，而不是当前 agent.loop。
- `ContextOrchestrator.assemble()` 创建 span 后没有 `makeCurrent()`；同时它直接使用 GlobalOpenTelemetry，没有统一通过已有 `ContextTraceRecorder` 写 required/fallback、预算、裁剪和压缩属性。

**影响：** `gen_ai.chat`、`tool.execute` 和 Memory span 仍与 `agent.loop` 平级，无法从 Context 装配追踪到本轮模型和工具调用。下属总结第 338-339 行也承认此问题未完成。

---

## 四、中优先级与完成度问题

### P2-1：Phase 6 清理未执行，最终架构仍可重新启用旧链路

仍存在以下计划明确要求删除的生产类型和入口：

- `ContextMode.java`、`HYBRID_EXTRA_CONTEXT` 及 `ContextBuildInput.mode`。
- `ContextOrchestrator.build()`、ContextSection/BuildResult/DebugInfo 兼容链。
- `ContextFrame` 中 memorySummary、vehicleStateSnapshot、timeContext、promptContext、renderedExtraContext、sections 等重复旧字段。
- AgentLoop 的 PromptManager、固定 `effectiveToolSpecs`、旧构造器和 `buildSystemPromptMessage()`。
- Service 仍向 TEXT Orchestrator 传 PromptManager 和固定全部 ToolSpecification。

非 TEXT 路径需要保留旧 execute 并不等于必须保留 TEXT 的旧所有权字段。当前类型系统仍未形成 Context 独占约束，Phase 6 的退出条件不成立。

### P2-2：空 TEXT 的权威校验没有实现

`AgentRuntime.execute():191-226` 没有在 Context 前校验 `session.userInput()` 的 null/blank。`INVALID_INPUT` 只存在于结果枚举和 Mapper 中，真实生产路径不会按计划产生该结果。下属总结第 335-336 行也明确承认未启用。

### P2-3：测试矩阵没有覆盖真实生产主链

- 强制重跑得到 41 个 suite、206 个 JVM test，0 failures/0 errors，但没有测试真实 `AgentLoopOrchestrator.execute(RequestSession, ContextPrepareResult)`。
- 没有覆盖第二次请求、SafetyGuard、生产 ToolRegistry 注入、预算超限、生产压缩重载、required Provider 中止、ToolExecutor 异常原子性等关键场景。
- `AgentLoopContextShadowTest.java:21-31` 只验证 lambda 可调用，不验证 AgentLoop。
- `SessionMemoryStoreDeleteTest.java` 没有 `@Test`，整个验证体被注释，仅是文档占位。
- scene/VL/CONTROL、设备 SQLite 和人工多轮场景均未执行；下属总结也承认非 TEXT 测试缺失。

### P2-4：构建总结与独立验证不一致

本轮强制执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --rerun-tasks
```

实际结果：

- `testDebugUnitTest`：执行完成，206 tests，0 failures，0 errors。
- `assembleDebug`：执行完成。
- `lintDebug`：失败，`98 errors / 35 warnings`；首个错误为 Manifest 系统权限 `ProtectedPermissions`。

这些 Lint 错误多数可能属于既有系统应用基线，不能全部归因于 Context 改造；但计划 Phase 6 明确要求 `lintDebug` 成功，下属总结也没有提供 baseline 或豁免说明，因此不能宣称最终验证全部通过。

---

## 五、Phase 逐阶段验收

| Phase | 验收结论 | 主要说明 |
|---|---|---|
| Phase 1：基线与强类型骨架 | 部分通过 | 类型和基础测试已建立，但 Validator 不满足唯一 System/ToolExchange 原子性，基线没有保护真实新 AgentLoop。 |
| Phase 2：Provider 真实化 | 不通过 | Provider 已创建，但 Message Contribution 不被消费，required 语义不生效，生产 ToolRegistry 未注入。 |
| Phase 3：影子装配 | 部分通过 | 迁移结构曾建立，但现存 ShadowTest 只是 lambda 冒烟测试，不能证明真实链路一致性。 |
| Phase 4：预算、压缩、Trace | 不通过 | 预算只估算且未接入；压缩只有能力 API；LLM/Tool span 仍挂 root。 |
| Phase 5：Context 独占切换 | 不通过 | 表面输入已切换，但工具为空、状态泄漏、安全守卫绕过、取消和错误语义缺失。 |
| Phase 6：清理与最终验收 | 不通过 | ContextMode/Section/旧构造依赖仍在，Lint 失败，设备及非 TEXT 回归未执行。 |

**整体判断：** Phase 1-3 形成了部分基础设施，Phase 4-6 的关键交付尚未闭环。不能按 6 个 Phase 完成计；也不建议在当前状态继续叠加新 Context 功能，应先修复 P0 并恢复完整执行管线，再完成预算/压缩/失败语义和清理。

---

## 六、建议整改顺序

1. **立即修复 P0：** 正确注入 ToolRegistry；补齐 AgentLoop 所有终态；恢复 SafetyGuard -> ToolExecutor -> ToolResult 的原有顺序，并增加真实 AgentLoop 连续请求和安全否决测试。
2. **恢复主链契约：** 使用 `prepareResult.currentUserMessage()`；让 Message Contribution 成为 Session/Current User 的唯一装配来源；required Provider 失败必须阻断。
3. **接通预算与压缩：** 将可注入 BudgetPolicy/Estimator 接入 assemble；实现裁剪顺序、一次压缩、原子写回、cache 失效、重读和二次装配。
4. **补齐取消和错误：** 在 prepare Provider 间、assemble 前后、压缩前后、模型前和工具前检查同一取消事实；按具体 ContextErrorCode/工具错误映射结果。
5. **修复消息与 Trace：** 按 Tool Call ID 验证完整交换；显式把 LLM/Tool/Memory span 挂到当前 agent.loop/context 层级。
6. **最后执行 Phase 6：** 删除 ContextMode、旧 Section/build、TEXT 旧构造依赖和固定工具字段；补齐非 TEXT、设备和人工场景验证，并说明 Lint 系统应用基线策略。

---

## 七、复验最低门禁

在重新申请 Phase 1-6 验收前，至少应提供以下新证据：

- 连续执行两个真实 TEXT 请求均成功，AgentLoop 每条终态均不保持 RUNNING。
- 明确车控、模糊车控、CHAT_ONLY 三类真实 ChatRequest 的工具集合断言。
- 高速开门被 SafetyGuard 否决，ToolExecutor 零调用。
- required Prompt/Tool/SessionMemory 失败时模型零调用；optional Provider 失败可降级。
- 小预算下能观察裁剪、一次压缩、重载、二次 assemble 和最终超限失败。
- cancel 在 assemble、模型前、工具前均能阻止后续副作用。
- Tool Calling 多请求、多结果、缺失结果、错误 ID 的序列校验测试。
- Trace 断言真实 parentSpanId：request root -> agent.loop -> context/LLM/tool/memory。
- `testDebugUnitTest`、`assembleDebug` 重新执行通过；`lintDebug` 通过或提供经项目确认的系统应用 Lint baseline；设备未执行项必须明确标注。

在上述门禁满足之前，本轮验收状态保持为：**拒绝验收。**
