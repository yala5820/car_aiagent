# Context 模块 Phase 6 验收审查

**审查日期：** 2026-07-07
**审查范围：** Phase 6 验收用例和非回归测试（Task 6.1～6.3）
**审查依据：** `docs/plan/context-module-implementation-plan.md` Phase 6 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无问题。** 本阶段纯加测试，不改生产代码。15 个 context 模块相关的测试用例全部通过，全量单测无回归。

---

## 二、本阶段做了什么

Phase 6 全是验收测试，下属在之前已有的测试基础上补了三大类验收用例：

### 1. ContextFrame 元信息验收（Task 6.1）

验证 ContextOrchestrator 产出的 ContextFrame 数据正确：

- `build_preservesUserSessionPersonaAndClientMessageId` — 验证 requestId/userId/sessionId/personaId/clientMessageId 从 RequestSession 完整透传到 ContextFrame，没有丢失或篡改。
- `build_containsIntentToolGroupAndSelectedTools` — 验证 IntentTag、selectedGroupIds、selectedToolNames 从 ToolGroupSelectionResult 正确进入 ContextFrame。
- `build_nullSessionReturnsFallback` — 验证传入 null session 不崩溃，返回 fallback 结果 + 明确错误原因。
- `fullContextMode_delegatesToHybridAndRecordsDeferred` — 验证 FULL_CONTEXT 降级为 HYBRID 行为，并在 debugInfo 记录 `full_context_deferred`。

### 2. 选中工具非全量验收（Task 6.2）

验证不同意图下 selectedToolNames 确实只包含相关工具，不是全量 47 个：

- `acRequest_selectedToolsAreAcAndBasicOnly` — 说"把空调打开"，只有 AC_GROUP + BASIC_STATUS_GROUP 的工具，不会有车窗或座椅工具。
- `windowRequest_selectedToolsAreWindowAndBasicOnly` — 说"打开车窗"，只有 WINDOW_GROUP 的工具。
- `seatRequest_selectedToolsAreSeatAndBasicOnly` — 说"打开座椅加热"，只有 SEAT_GROUP 的工具。
- `chatRequest_selectedToolsAreNotAllTools` — 普通聊天选中工具数少于 47。

每个测试还额外断言 `frameRef.get().selectedToolNames()` 和 `session.toolGroupSelectionResult().selectedToolNames()` 一致，确认 ContextFrame 和 ToolGroupSelection 是同一份数据。

### 3. 不重复注入验收（Task 6.3）

`renderedExtraContext_doesNotDuplicateExistingAgentLoopContexts` 验证：
- `renderedExtraContext` 不含 `"当前时间："`（TimeContextProvider renderable=false）
- 不含 `"【长期记忆】"`（MemoryContextProvider renderable=false）
- 不含 `"车辆状态："`（VehicleStateContextProvider renderable=false）
- 不含 `"你是"`（system prompt 文本，PromptContextProvider renderable=false）

---

## 三、代码审查

### 3.1 新增 / 修改的测试

| 文件 | Phase 6 新增用例 | 所属 Task |
|------|----------------|-----------|
| `ContextOrchestratorTest.java` | `fullContextMode_delegatesToHybridAndRecordsDeferred` | 6.1 |
| | `build_nullSessionReturnsFallback` | 6.1 |
| | `build_preservesUserSessionPersonaAndClientMessageId` | 6.1 |
| | `build_containsIntentToolGroupAndSelectedTools` | 6.1 |
| | `renderedExtraContext_doesNotDuplicateExistingAgentLoopContexts` | 6.3 |
| `AgentRuntimeContextTest.java` | `acRequest_selectedToolsAreAcAndBasicOnly` | 6.2 |
| | `windowRequest_selectedToolsAreWindowAndBasicOnly` | 6.2 |
| | `seatRequest_selectedToolsAreSeatAndBasicOnly` | 6.2 |
| | `chatRequest_selectedToolsAreNotAllTools` | 6.2 |

### 3.2 ContextOrchestrator 新增 null session 保护

`build()` 方法入口增加了 guard clause：

```java
if (session == null) {
    ContextFrame emptyFrame = new ContextFrameBuilder()
            .mode(input.mode()).effectivePersonaId("chat")
            .renderedExtraContext("").build();
    return ContextBuildResult.fallback(emptyFrame, "session_is_null");
}
```

这是 Phase 6 中唯一的生产代码变更，不是计划强制要求的，但提高了健壮性。空 frame 的 ID 字段为 null，但 fallback 场景下游不会消费这些字段，可接受。

### 3.3 `build_nullSessionReturnsFallback` 测试边界

该测试不在计划的 Phase 6 任务列表中，是下属额外增加的边界测试。覆盖了上游传 null session 的异常场景，属于有价值的补充。

---

## 四、审查结论

**Phase 6 通过验收，可以进入 Phase 7。**

| 检查项 | 状态 |
|--------|------|
| Task 6.1：ContextFrame 元信息验收（2 测试） | ✅ |
| Task 6.2：selectedToolNames 非全量工具（4 测试） | ✅ |
| Task 6.3：不重复注入验收（1 测试 + 复用 Phase 4 测试） | ✅ |
| 额外边界测试（null session / FULL_CONTEXT） | ✅ 超出计划要求 |
| 全量单测 | ✅ BUILD SUCCESSFUL |
