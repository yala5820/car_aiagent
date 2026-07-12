# Memory Session ChatMemory Phase 4 验收审查

**审查日期：** 2026-07-09
**审查范围：** Phase 4 AgentLoop 极窄接入（Task 4.0～4.3）
**审查依据：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md` Phase 4 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无阻塞问题。** Phase 4 核心改动全部正确：SystemMessage 去持久化、session-scoped ChatMemory、speaker 标记、Service 清理修正。`AgentLoopOrchestrator` 中的 sessionId 强制检查在 demo 阶段只影响 TEXT 路径（必然携带 sessionId），VOICE 和场景识别路径不在当前工作范围内，不构成实际回归。

---

## 二、本阶段完成的工作

### Task 4.0：SystemMessage 去持久化 ✅

- `AgentLoopOrchestrator` 新增 `buildSystemPromptMessage()` → 每轮局部构建 SystemMessage（含长期记忆），不写入 ChatMemory
- 请求消息组装变为：`transient SystemMessage + transientMessages + chatMemory.messages()`
- `injectSystemPrompt()` 整个方法被删除，不再向持久化 ChatMemory 写入 SystemMessage
- `MemoryPreProcessor` 从 `createTextPersona()` 和 `createChatPersona()` 的 preprocessor 链中移除，遗留为 legacy/no-op

### Task 4.1：AgentLoop 使用 session-scoped ChatMemory ✅

- `chatMemory` 字段改名为 `fallbackChatMemory`，仅用于 legacy 路径
- `execute()` 从 extraContext 读取 `session_id`，调用 `memoryOrchestrator.chatMemoryForSession(sessionId, maxMessages)`
- 用户消息带 speaker 标记写入：`[speaker=user_a] 打开空调`
- `onTurnComplete` 传 `sessionId`，压缩写回通过 `replaceMessages` 同步 provider 缓存
- `cleanMemory(sessionId)` 新增用于 session-scoped 清理

### Task 4.2：停止固定 ChatMemory 主路径 ✅

- `createTextPersona()` 和 `createChatPersona()` 的 `chatMemoryStoreId` 改为 `"FallbackChatMemory"`，附带中文注释说明

### Task 4.3：修正 Service 中 ClearChatMemory ✅

- `ClearChatMemory` 不再把 `request.sessionId` 当 `userId` 传，改为：
  1. `normalizeUserId(request)` 获取 userId
  2. 缺失 sessionId 时通过 `resolveSessionId` 解析
  3. `textOrchestrator.cleanMemory(sessionId)` 清理

---

## 三、观察项

### 观察 1：VOICE / 场景识别路径未传 sessionId（demo 阶段不涉及）

`AgentLoopOrchestrator` 当前要求 `extraContext` 中必须包含 `session_id`，否则返回 `INVALID_CONFIG`：

| 路径 | 是否传 session_id | demo 阶段 |
|------|------------------|-----------|
| TEXT（Runtime 路径） | ✅ 由 RequestSessionFactory 写入 | 使用中 |
| VOICE（`chatOrchestrator.execute()`） | ❌ 无 | 不在 demo 范围 |
| 场景识别（`sceneOrchestrator.execute()`） | ❌ 无 | 不在 demo 范围，可能删除 |

当前 demo 阶段只有 TEXT 路径在使用，不受影响。后续如果启用 VOICE 或保留场景识别，需要在那之前加上 fallback 逻辑。

---

## 四、确认正确的设计

| 检查项 | 状态 |
|--------|------|
| SystemMessage 改为 transient，不写入 chatMemory | ✅ |
| chatMemory 改 fallbackChatMemory 字段名 | ✅ |
| TEXT 路径用 `chatMemoryForSession(sessionId, maxMessages)` | ✅ |
| 用户消息带 `[speaker=xxx]` 标记 | ✅ |
| `onTurnComplete` 传 sessionId | ✅ |
| 压缩写回通过 `replaceMessages` 同步缓存 | ✅ |
| `createTextPersona` preprocessor 链无 MemoryPreProcessor | ✅ |
| `createChatPersona` preprocessor 链无 MemoryPreProcessor | ✅ |
| MemoryPreProcessor 保留为 legacy/no-op | ✅ |
| `chatMemoryStoreId` 改为 `"FallbackChatMemory"` + 注释 | ✅ |
| ClearChatMemory 修正 userId/sessionId 混淆 | ✅ |
| 全量单测 | ✅ BUILD SUCCESSFUL（但未覆盖 VOICE/Scene 运行时路径） |

---

## 五、审查结论

**Phase 4 通过验收，可以进入 Phase 5。**
