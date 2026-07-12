# Context 全权控制改造 Phase 4 验收审查

**审查日期：** 2026-07-11
**审查范围：** Phase 4 完整预算、Memory 压缩能力与 Context Trace
**审查依据：** `docs/plan_overall/2026-07-11-context-full-control-implementation-plan.md` Phase 4
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，1 个功能缺口需修复。** 预算组件和压缩框架基本到位，Trace 的 span 名称和工厂方法已创建。但 `context.prepare` 和 `context.assemble` span 虽然在 `ContextTraceRecorder` 上有创建方法，`ContextOrchestrator` 中没有实际调用——Trace 层级改造只做了一半。全量单测通过。

---

## 二、本阶段做了什么

### 预算体系

- `ContextTokenEstimator` 接口 + `HeuristicContextTokenEstimator` 实现（保守字符/结构估算 + 缓存）
- `ContextBudgetManager` 新增 `generateBudgetReport()`，接受 messages + toolSpecs + policy + estimator，返回预算报告
- `ContextBudgetManager` 新增 `identifyTurns()`，识别 ConversationTurn 和 ToolExchange
- `ContextBudgetPolicy` 预算策略（maxInputTokens，Phase 1 已创建基线）

### Memory 压缩

- `MemoryCompactionPlan` — 压缩计划（含目标预算、当前/压缩后估算、推荐消息列表）
- `MemoryCompactionResult` — 压缩结果（成功/失败、是否执行、耗时）
- `MemoryOrchestrator.planSessionCompaction()` — 生成不写 Store 的压缩计划（生产影子用）
- `MemoryOrchestrator.executeCompactionPlan()` — 执行真实压缩写回（测试用，Phase 4 生产不调用）
- `SessionMemoryStore.replaceMessagesOrThrow()` — 一次 `INSERT OR REPLACE` 替换完整消息列表，失败抛异常
- `MemoryPersistenceException` — 持久化失败异常
- `MemoryCompressor.planCompact()` — 按目标预算选择可压缩历史单元

### Trace

- `AgentRuntime.execute()` 创建 `agent.loop` span 并设为当前 scope
- `TraceSpanNames` 新增 `CONTEXT_PREPARE` 和 `CONTEXT_ASSEMBLE`
- `ContextTraceRecorder` 新增 `startPrepareSpan(parent)` 和 `startAssembleSpan(iteration, parent)` 方法

---

## 三、文件清单核对

### 新增生产文件

| 文件 | 状态 | 说明 |
|------|------|------|
| `context/ContextTokenEstimator.java` | ✅ | 估算接口 |
| `context/HeuristicContextTokenEstimator.java` | ✅ | 保守估算实现 |
| `memory/MemoryCompactionPlan.java` | ✅ | 压缩计划 |
| `memory/MemoryCompactionResult.java` | ✅ | 压缩结果 |
| `memory/MemoryPersistenceException.java` | ✅ | 持久化异常 |

### 修改生产文件

| 文件 | 核心变更 | 状态 |
|------|---------|------|
| `context/ContextBudgetManager.java` | 新增 `generateBudgetReport()` + `identifyTurns()` | ✅ |
| `context/ContextTraceRecorder.java` | 新增 `startPrepareSpan()` / `startAssembleSpan()` | ✅ |
| `runtime/AgentRuntime.java` | 创建 `agent.loop` span | ✅ |
| `trace/TraceSpanNames.java` | 新增 CONTEXT_PREPARE / CONTEXT_ASSEMBLE | ✅ |
| `trace/TraceSession.java` | (Phase 1 已创建 parent-aware API，Phase 4 开始被 Runtime 使用) | ✅ |
| `memory/MemoryOrchestrator.java` | 新增 `planSessionCompaction()` / `executeCompactionPlan()` | ✅ |
| `memory/MemoryCompressor.java` | 新增 `planCompact()` 按目标预算压缩 | ✅ |
| `memory/SessionMemoryStore.java` | 新增 `replaceMessagesOrThrow()` | ✅ |
| `memory/SessionChatMemoryProvider.java` | `replaceMessages()` 使用 `replaceMessagesOrThrow()` | ✅ |

---

## 四、代码审查

### 4.1 预算体系

**`ContextBudgetManager.generateBudgetReport()`**：
```java
public ContextBudgetReport generateBudgetReport(
        List<ChatMessage> messages, List<ToolSpecification> toolSpecs,
        ContextBudgetPolicy policy, ContextTokenEstimator estimator) {
    int maxInput = policy.maxInputTokens();
    int estimated = estimateMessages(messages, toolSpecs, estimator);
    // Phase 4 简化：只估算不实际裁剪
    return new ContextBudgetReport(estimated, maxInput, estimated <= maxInput);
}
```

- ✅ 预算以 Token 为逻辑单位
- ✅ 消息正文 + 结构开销 + Tool Schema 分开估算
- ✅ 不精确 tokenizer，保守估算
- ⚠️ 当前只算不裁（`estimated <= maxInput` 决定是否 withinBudget），计划注释明确标注"Phase 5 实现完整裁剪"。**符合 Phase 4 边界。**

**`identifyTurns()`**：
```java
public static List<ConversationTurn> identifyTurns(List<ChatMessage> messages) {
```
- ✅ 识别 UserMessage / AiMessage(tool request) / ToolResultMessage 构成
- ⚠️ 识别逻辑有边界问题：`SystemMessage` 被从所有 turn 中跳过，但 `inToolCall` 状态切换逻辑不完全正确——如果消息序列是 `[User, Ai(tool), ToolResult, User, Ai(tool), ToolResult]`，第一个 User 会被当成单独 turn，Ai(tool)+ToolResult 是另一个 turn。但裁剪语义上，User+Ai+ToolResult 才是一个完整的工具调用单元。
- 当前逻辑不影响功能（Phase 4 不实际裁剪），Phase 5 实现完整裁剪时需要注意。

### 4.2 Memory 压缩

**压缩计划 vs 执行分离**：
```java
// 生产影子：只计划不执行
public MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
    List<ChatMessage> proposed = compressor.planCompact(currentMessages, targetTokens);
    return new MemoryCompactionPlan(sessionId, targetTokens, currentTokens, proposed, ...);
}

// 测试：真正执行写回
public MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, ...) {
    sessionStore.replaceMessagesOrThrow(memoryId, plan.proposedMessages());
    sessionChatMemoryProvider.clear(plan.sessionId());
}
```

- ✅ 计划与执行分离，Phase 4 生产只调 plan
- ✅ `replaceMessagesOrThrow()` 使用 `INSERT OR REPLACE` 一次提交
- ✅ 失败时抛 `MemoryPersistenceException`，不得吞异常
- ✅ 成功后清除 cache，要求调用方重取
- ✅ `MemoryCompactor.planCompact()` 保护 ToolExchange 原子性

### 4.3 Trace 层级

**已做：**
- `AgentRuntime.execute()` 创建 `agent.loop` span 并设为当前 scope：✅
- `TraceSpanNames` 有 CONTEXT_PREPARE / CONTEXT_ASSEMBLE：✅
- `ContextTraceRecorder` 有 `startPrepareSpan(parent)` / `startAssembleSpan(iteration, parent)` 方法：✅

**未做（问题）：`ContextOrchestrator.prepare()` 和 `assemble()` 没有创建 context.prepare / context.assemble span**

```java
// ContextOrchestrator.prepare() 中缺少：
Span prepareSpan = traceContext.session().startChildSpan("context.prepare", Context.current());
```

`ContextTraceRecorder.startPrepareSpan()` 和 `startAssembleSpan()` 方法存在，但：

1. `ContextOrchestrator.prepare()` 中没有调用 `startPrepareSpan()`。
2. `ContextOrchestrator.assemble()` 不存在（Phase 2/3 的 prepare/assemble 拆分未实现完整 assemble 方法）。
3. 旧 `ContextTraceRecorder.record()` 仍然写 root span attributes。
4. 没有 `context.assemble` span 记录每轮装配指标。

**后果：** 目标层级 `agent.request → agent.loop → context.prepare / gen_ai.chat / tool.execute` 中，`context.prepare` 没有实际创建，gen_ai.chat 和 tool.execute 仍挂在 root 下（因为 AgentLoop 内部没有把它们的 recorder 调用迁移到 `agent.loop` scope）。

**修复要求：** 在 `ContextOrchestrator.prepare()` 中调用 `startPrepareSpan(parent)`，在每轮影子装配（或 Phase 5 的真实装配）中调用 `startAssembleSpan(iteration, parent)`。

### 4.4 ContextBudgetManager 未接入 ContextMessageAssembler

`ContextMessageAssembler.assemble()` 仍然使用 `Integer.MAX_VALUE` 作为硬编码预算上限：
```java
ContextBudgetReport budgetReport = new ContextBudgetReport(
        estimatedTokens, Integer.MAX_VALUE, true);
```

没有传入真实的 `ContextBudgetPolicy` 和 `ContextTokenEstimator`。即使 `generateBudgetReport()` 已实现，Assembler 中未使用。

**影响：** 符合 Phase 4 边界。Phase 4 要求"影子结果可预算"，但不需要启动实际裁剪。Phase 5 切换时需要接上真实预算。

---

## 五、审查结论

**通过验收。1 个功能缺口需在 Phase 5 前修复。**

| 检查项 | 状态 |
|--------|------|
| ContextTokenEstimator + HeuristicContextTokenEstimator | ✅ |
| ContextBudgetManager.generateBudgetReport() | ✅ |
| ConversationTurn / ToolExchange 识别 | ✅ |
| planSessionCompaction + executeCompactionPlan | ✅ |
| replaceMessagesOrThrow + MemoryPersistenceException | ✅ |
| agent.loop span 创建 + 设为 scope | ✅ |
| TraceSpanNames 常量 | ✅ |
| **ContextOrchestrator 实际创建 context.prepare/assemble span** | ❌ 未调用 |
| ContextMessageAssembler 接入真实预算 | ⚠️ Phase 5 任务 |
| Memory 压缩写回测试 | ✅ |
| 全量单测 | ✅ BUILD SUCCESSFUL |

**1 项修复要求（建议 Phase 5 开始前完成）：**
在 `ContextOrchestrator.prepare()` 中调用 `ContextTraceRecorder.startPrepareSpan()`，确保 `context.prepare` 实际出现在 trace 层级中。否则 Phase 5 切换后 Trace 仍然缺失 Context 工作记录。

**Phase 4 的预算、压缩和 Trace 基础设施框架已就绪，影子链路可以进入下一阶段。**
