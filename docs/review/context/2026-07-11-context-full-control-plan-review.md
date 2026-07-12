

## 三、实现计划审查

#### 问题 B（阻塞）：Trace 父子层级改造范围被低估

计划 2.11 和 4.4 要求：
- 新增 `agent.loop` span，让它成为 context.assemble、gen_ai.chat、tool.execute 的父 span
- `TraceSession` 增加显式 parent 版本：`startChildSpan(String, io.opentelemetry.context.Context)`
- 修复当前"所有 span 挂 root"的问题
- 删除 `prompt.assembly`，替换为 `context.prepare/context.assemble`

但当前 `TraceSession` 的代码中，`startChildSpan`、`startLlmSpan`、`startToolSpan` 都可能固定用了 rootContext。要改成 parent-aware，需要：
1. 修改 `TraceSession.java` 的核心 span 创建逻辑
2. 修改 `AgentTraceRecorder.java`、`LlmTraceRecorder.java`、`ToolTraceRecorder.java` 等所有 recorder
3. 确保 `agent.loop` span 的生命周期正确（在 Runtime.execute() 中创建，在 finally 中结束）
4. 修改所有断言 trace 层级的测试

这和 Context 模块本身的核心改动（Provider、Assembler、Budget）没有直接关系，但必须完成才能满足最终验收标准第 12 条。

**建议：** 
- 在 Phase 中明确标出 Trace 改造的**预估工作量和风险**
- 考虑在 Phase 1 或 Phase 2 提前完成 TraceSession 的 parent-aware API 改造（因为改完后不影响旧链路，且 Phase 4 直接可用）
- 在测试中增加 `TraceSessionParentChildTest` 明确验证层级，而不是靠散落在各处的断言

---

### 3.2 非阻塞但需关注的问题

#### 问题 3：AgentExecutor 接口变更会波及大量现有测试

Phase 3 将 `AgentExecutor` 从 `execute(String, Map)` 改为 `execute(RequestSession, ContextPrepareResult)`。这会影响到：
- `AgentRuntimeTest.java`（6+ 个测试 lambda）
- `AgentRuntimeContextTest.java`（6+ 个测试 lambda）
- `AgentExecutorCompatibilityTest.java`（需要完全重写）
- `AIAgentService.kt` 中的匿名 lambda

计划提到"同一 Phase 更新所有 lambda，不保留双 SAM adapter"，但`AgentExecutor` 当前是 `@FunctionalInterface`，改了抽象方法签名后所有 lambda 都会编译失败。这些 lambda 分散在 3-4 个文件中，需要逐个修改。

**建议：** 在 Phase 3 Task 3.1 中明确列出所有需要修改的 lambda 位置，避免遗漏。

#### 问题 4：`ContextCancelChecker` 与现有 `RuntimeCancelChecker` 职责重叠

计划新增 `ContextCancelChecker`（Context 装配时的取消入口），但当前已有 `RuntimeCancelChecker`（Context 构建后、AgentLoop 前）。两者的区别是：
- `RuntimeCancelChecker`：在 `AgentRuntime.execute()` 内部、Context build 后检查
- `ContextCancelChecker`：在 `ContextOrchestrator.assemble()` 内部、每轮装配前检查

两组取消检查点是正确的，但两个接口名称相似容易混淆。此外，`ContextCancelChecker` 的实现可能需要访问 `ActiveRequestRegistry`（现有取消来源），而计划说"不让 Context 依赖 ActiveRequestRegistry 具体类"。

**建议：** 保持职责分离，但明确标注 `RuntimeCancelChecker` 和 `ContextCancelChecker` 的区别。建议在 Runtime 层把 `ContextCancelChecker` 的实现注入给 ContextOrchestrator，而不是让 Context 直接持有 ActiveRequestRegistry。

#### 问题 5：`MemorySnapshot.sessionId()` 的 bug 修复有 Breaking Change 风险

计划 Phase 2 要求修正 `MemorySnapshot.sessionId()` 返回 `memory:<sessionId>` 的 bug。但当前代码中可能有其他调用者依赖这个行为（比如 Trace 记录、日志、SessionChatMemoryProvider 的 key 计算）。如果只改了 MemorySnapshot 但没改调用者，会导致 sessionId 不匹配。

**建议：** 修之前搜索所有 `memoryId`、`shortTermMemoryId`、`sessionId()` 的调用点，确认影响范围后再改。建议专门写一个测试来验证 sessionId 的传递链路。

#### 问题 6：影子比较的"允许差异表"需要具体化

计划 3.2 列出了影子比较的 6 类允许差异，但这些都是定性描述（如"内部 userId/sessionId 不再进入 LLM"）。实际执行时，开发者需要知道"具体哪条消息、哪个字段被移除了"才能写比较器。

**建议：** 在 Phase 1 的 `LegacyTextInputCharacterizationTest` 中先固化 5-8 个典型场景的实际消息列表（用 CapturingModelCaller 捕获），然后把这些场景的"旧输出 vs 新输出"对比表作为允许差异的附件。这样开发者和审查者看到的是具体消息差异，而不是"原则上同意"。

#### 问题 7：ChatMemory 写一次 + 读一次的顺序依赖

计划 2.5.3 要求：
1. AgentLoop 在 iteration 0 前把 currentUserMessage 写入 ChatMemory
2. assemble() 的 SessionMemory Provider 读取 ChatMemory（此时已包含该消息）
3. iteration 1 及以后禁止再次写入

这个顺序在正常流程下没问题，但如果 iteration 0 的 assemble() 失败了或取消了，AgentLoop 已经写入了 currentUserMessage。后续不再有模型调用，但 ChatMemory 中多了一条未完成的 UserMessage。

这不是致命问题（用户下次发消息时，这条消息在上下文中是合理的），但需要注意这不是"写入事务"——写入后如果请求取消，不会回滚。

**建议：** 在文档中明确记录这一行为——ChatMemory 写入是"单向的"，取消不回滚。这不是 bug，是设计决策，但后续排查时如果看到孤立消息需要知道原因。

#### 问题 8：RequestSession 空文本处理的边界

计划说"TEXT 正常请求若 input 为空，应由 Runtime/现有请求校验拒绝"。但当前的 `AIAgentService.handleTextRequest()` 并没有在入口处 reject 空文本——`request.text ?: ""` 会把 null 变成空字符串。所以空文本请求有可能到达 Context。

如果空文本到达了 UserInput Provider，按计划"Context 不创建空 UserMessage"，但 AgentLoop 需要 currentUserMessage 来写入 ChatMemory。这会导致 AgentLoop 没有消息可写。

**建议：** 在 Service 或 Runtime 层增加空文本校验，或者 UserInput Provider 对空文本返回 CONTEXT_REQUIRED_PROVIDER_FAILED。建议选前者（前置校验），因为空文本本来就是无效请求，不应该走到 Provider。

---
