# Memory Session ChatMemory Phase 3 验收审查

**审查日期：** 2026-07-09
**审查范围：** Phase 3 SessionChatMemoryProvider 和 Context 预留只读接口（Task 3.1～3.3）
**审查依据：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md` Phase 3 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，发现 1 个非阻塞风险。** 本阶段完成了 3 件事：

1. **新增 `SessionChatMemoryProvider`** — 封装 LangChain4j `MessageWindowChatMemory` + `SessionMemoryStore`，带 LRU 缓存淘汰，暴露 `getOrCreate`、`replaceMessages`、`clear`
2. **新增 `MemorySnapshot` 只读快照** — Context 预留用，不可变，防御性拷贝
3. **`MemoryOrchestrator` 接入 provider** — 暴露 5 个预留接口 + 带 sessionId 的新 `onTurnComplete` 重载

---

## 二、文件清单核对

### 2.1 新增文件

| 文件 | 状态 | 说明 |
|------|------|------|
| `memory/SessionChatMemoryProvider.java` | ✅ | LRU 缓存 + replaceMessages 同步 live/store + clear |
| `memory/MemorySnapshot.java` | ✅ | 不可变快照，防御性拷贝 |
| `test/.../memory/SessionChatMemoryProviderTest.java` | ✅ | 4 测试：同 session 复用、隔离、LRU 淘汰、replace 立即可见 |
| `test/.../memory/MemorySnapshotTest.java` | ✅ | 1 测试：防御性拷贝 |

### 2.2 修改文件

| 文件 | 变更 | 状态 |
|------|------|------|
| `memory/MemoryOrchestrator.java` | +5 预留接口 + sessionId onTurnComplete 重载 | ✅ |
| `memory/UserMemoryContext.java` | 无变化（仅前置依赖，无需修改） | ✅ |

---

## 三、接口清单确认

### SessionChatMemoryProvider 接口

| 方法 | 用途 | 状态 |
|------|------|------|
| `getOrCreate(sessionId)` | 默认窗口创建 | ✅ |
| `getOrCreate(sessionId, maxMessages)` | 指定窗口创建 | ✅ |
| `replaceMessages(sessionId, messages)` | 压缩写回，同步 live + store | ✅ |
| `clear(sessionId)` | 清除缓存和 store | ✅ |

LRU 缓存基于 `LinkedHashMap` 的 `removeEldestEntry`，`accessOrder=true`，默认上限 50。

### MemoryOrchestrator 新增接口

| 方法 | 用途 | 备注 |
|------|------|------|
| `chatMemoryForSession(sessionId, maxMessages)` | TEXT 主路径获取 ChatMemory | Phase 4 使用 |
| `clearSessionMemory(sessionId)` | 清理 session 记忆 | |
| `readSessionMessages(sessionId)` | Context 只读快照 | 直读 store，不走缓存 |
| `getMemorySnapshot(sessionId)` | Context 预留快照 | 含消息列表+摘要+估算 token |
| `getSummarizedMemory(sessionId, maxChars)` | Context 预留摘要 | 按字符预算截断 |
| `onTurnComplete(userId, sessionId, ...)` | 压缩写回新重载 | 通过 replaceMessages 同步 |

---

## 四、发现的问题

### 问题 1（风险）：旧 `onTurnComplete` 重载压缩后不更新 provider 缓存

```java
// MemoryOrchestrator.java:93-101 — 旧重载
List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
if (compressed != currentMessages) {
    String memoryId = ctx.currentMemoryId();
    if (memoryId != null) {
        sessionStore.updateMessages(memoryId, compressed);  // ← 直接写 store
    }
}
```

旧重载压缩写回走 `sessionStore.updateMessages()`，不通过 `sessionChatMemoryProvider.replaceMessages()`。这导致 provider 缓存中的 live `ChatMemory` 仍然是压缩前的旧消息。下一次 `getOrCreate` 从缓存取到的是过期数据。

**影响范围：** 仅影响旧重载调用方。计划要求 Phase 4 TEXT 主路径必须使用带 `sessionId` 的新重载（走 `replaceMessages`），所以不会影响 Phase 4 后的正常 TEXT 路径。旧重载保留给测试/legacy 调用方。

**建议：** 旧重载在注释中标注 "compression write-back bypasses provider cache"，让维护者清楚这个限制。

---

## 五、确认无误的设计

| 设计点 | 结论 |
|--------|------|
| SessionChatMemoryProvider 使用 `shortTermMemoryId` 校验 sessionId | ✅ |
| LRU 缓存上限 50 | ✅ |
| `replaceMessages` 后 `getOrCreate` 立即可见新数据 | ✅ 测试覆盖 |
| `MemorySnapshot` 不可变 + 防御性拷贝 | ✅ |
| `getMemorySnapshot` 只读现有摘要不触发压缩 | ✅ |
| `getSummarizedMemory` 按 maxChars 截断 | ✅ |
| `readSessionMessages` 直读 store 不热加载缓存 | ✅ |
| 新 `onTurnComplete` 通过 `replaceMessages` 写回 | ✅ |
| 全量 memory 测试 | ✅ BUILD SUCCESSFUL |
