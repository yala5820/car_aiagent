# Memory Session ChatMemory Phase 1 验收审查

**审查日期：** 2026-07-09
**审查范围：** Phase 1 短期记忆身份模型和纯函数测试（Task 1.1～1.2）
**审查依据：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md` Phase 1 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无问题。** 本阶段纯加工具类和单测，不改任何生产逻辑链路。两个 Task 都严格按计划实现，测试覆盖完整。

---

## 二、本阶段做了什么

### Task 1.1: 收敛 `SessionMemoryIds`

将短期 memory key 从旧的 `{userId}_{sessionId}_{personaId}` 格式收敛为只由 `sessionId` 决定：

- `shortTermMemoryId(sessionId)` — 唯一短期 memory key 入口。接收 Phase 0 已解析的非空 sessionId，返回 `sessionId.trim()`。传入 null/空白时抛 `IllegalArgumentException`（因为 Phase 0 保证不会出现这种情况）
- `shortTermMemoryPrefix(sessionId)` — 与 `shortTermMemoryId` 返回值相同。短期 key 已不需要 LIKE 前缀匹配
- `build()` / `buildPrefix()` — 标记 `@Deprecated`，内部委托给新方法

4 个测试覆盖：正常 sessionId、空白/ null 拒绝、同 sessionId 幂等性、prefix 一致性。

### Task 1.2: 增加 `SpeakerMessageFormatter`

`formatUserMessage(userId, text)` → 返回 `[speaker={userId}] {text}`。空白 userId 降级为 `default_user`，null text 降级为空字符串。

3 个测试覆盖：正常格式、空白 userId 降级、null text 降级。

---

## 三、审查结论

**Phase 1 通过验收，可以进入 Phase 2。**

| 检查项 | 状态 |
|--------|------|
| `shortTermMemoryId` 仅用 sessionId | ✅ |
| 空白/ null 抛 IllegalArgumentException | ✅ |
| `shortTermMemoryPrefix` 与 Id 一致 | ✅ |
| 旧方法标记 @Deprecated 委托新方法 | ✅ |
| `formatUserMessage` 带 speaker 前缀 | ✅ |
| 空白 userId 降级 default_user | ✅ |
| 全部 7 个新测试通过 | ✅ |
| 全量 memory 测试无回归 | ✅ BUILD SUCCESSFUL |
