# Memory Session ChatMemory 改造计划 审查报告

**审查日期：** 2026-07-09
**审查范围：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md`
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，2 个阻塞问题需修正后方可启动实施。** 整体方案方向正确——短期 memory key 收敛到 `sessionId`、长期记忆保持 `userId` 维度、通过 `SessionChatMemoryProvider` 封装 LangChain4j 底层。Phase 编排合理（先模型再存储再接入再加固）。但存在两个核心问题：多用户共享会话时的长记忆数据泄漏，以及预留接口定义不明确。

---

## 二、阻塞问题

### 问题 1（阻塞）：Phase 4 引入多用户长记忆数据泄漏，修复却延到 Phase 5

**描述：**

当前 `AgentLoopOrchestrator.injectSystemPrompt()` 把 SystemMessage（含当前用户长期记忆）写入 `chatMemory`，而 `chatMemory` 自动通过 `ChatMemoryStore` 持久化到 `session_messages` 表。

Phase 4 让 `chatMemory` 变成 session 级别共享后，这个过程变成：

```
User A 发言 → chatMemory = session-1 的 ChatMemory
            → injectSystemPrompt() 写入 SystemMessage(User A 的长期记忆)
            → 持久化到 session_messages WHERE memory_id = "session-1"

User B 发言 → chatMemory = 同一个 session-1 的 ChatMemory
            → chatMemory.messages() 第一条是 User A 的 SystemMessage
            → User A 的长期记忆被 User B 看到
```

**这是跨用户的数据泄漏。** User A 的偏好（"我喜欢 22 度"）会在 User B 的上下文中出现，因为 SystemMessage 被持久化到了共享的 session store。

**问题根因：** Phase 4 让 ChatMemory 变为 session-scoped，但 `injectSystemPrompt()` 仍在向 ChatMemory 写入用户维度的长期记忆。修复逻辑在 Phase 5 Task 5.2，但 Phase 4 的实施会先引入 bug。

**建议修复方案：** 将 Phase 5 Task 5.2 的 SystemMessage 去持久化工作移到 Phase 4，两阶段合并执行。具体来说：Phase 4 必须同时完成"ChatMemory 按 session 选择"和"SystemMessage 不写入 ChatMemory，改为本轮 transient 组装"。否则 Phase 4 交付的代码包含跨用户数据泄漏。

### 问题 2（阻塞）：未具体定义"预留 Context 接入接口"

**描述：**

任务目标明确要求"仅预留相关功能接口"，计划中也提到"为后续 Context 模块接管本轮 prompt 组装预留干净接口"，但计划中没有任何具体接口定义。

当前计划暴露的接口：
- `MemoryOrchestrator.chatMemoryForSession(sessionId)` → 返回 `ChatMemory` 实例（上游可以直接读消息）
- `MemoryOrchestrator.clearSessionMemory(sessionId)` → 清理

但对于"Context 接管 prompt 组装"这个目标，Memory 模块至少需要暴露：

1. **按 sessionId 读取短期消息** — ContextOrchestrator 的 `MemoryContextProvider` 需要获取当前 session 的消息列表或摘要，才能决定是否/如何渲染到 extra context 中。
2. **按 sessionId + budget 返回压缩摘要** — 如果 Context 要决定本轮带多少记忆、要不要压缩，需要一个 `readMemory(sessionId, maxTokens)` 接口。
3. **通知 Memory 模块"本轮 Context 已接管 prompt 组装"** — 需要一个标记机制，让 Memory 模块知道本轮不需要通过旧路径（SystemPrompt 写入 ChatMemory）注入长期记忆。

**建议：** 在 Phase 3（新增 `SessionChatMemoryProvider` 的阶段）或 Phase 5（边界加固阶段）中明确以下接口之一：

```java
// 方案 A：只读接口（最轻量）
public List<ChatMessage> readSessionMessages(String sessionId);
public String getSummarizedMemory(String sessionId, int maxChars);

// 方案 B：带预算的读取（更完整）
public MemorySnapshot getMemorySnapshot(String sessionId, MemoryBudget budget);
```

选择哪个方案取决于"Context 接管"的具体粒度。当前计划中没有选择，建议开工前确认。

---

## 三、重要但非阻塞问题

### 问题 3：`MemoryPreProcessor` 的长期记忆重复注入未被处理

现有代码中（`memory-module-overview.md` 已记录的问题 1），同一个长期记忆内容在同一轮 LLM 调用中出现两次：
1. SystemMessage 尾部（由 `injectSystemPrompt` → `MemoryOrchestrator.prepareSystemPrompt()` 注入）
2. UserMessage （由 `MemoryPreProcessor` 注入）

当前计划没有包含修复此问题的 Task。即使在 Phase 5 完成了 SystemMessage 去持久化，`MemoryPreProcessor` 仍然会在首轮注入一条包含长期记忆的 UserMessage，与 SystemMessage 的长期记忆重复。

**建议：** 在 Phase 5 Task 5.2（SystemMessage 去持久化）之后，同步停用或清理 `MemoryPreProcessor` 中的长期记忆注入逻辑，只保留一个注入点。

### 问题 4：`SessionChatMemoryProvider` 的内存缓存无淘汰机制

`SessionChatMemoryProvider` 使用 `ConcurrentHashMap<String, ChatMemory>` 缓存 ChatMemory 实例。随着时间推移和会话切换，缓存中堆积的 ChatMemory 实例会持续增长。每个 ChatMemory 实例持有 `MessageWindowChatMemory`（含消息列表和 ChatMemoryStore 引用）。

对于一个长期运行的 Android Service，这可能导致 OOM 或 GC 压力。

**建议：** 至少添加：
- 一个 `maxCachedSessions` 上限（如 50），超过时淘汰最久未访问的
- 或者在 `clear()` 之后从缓存移除
- 最简单的方案是用 `LinkedHashMap` + `removeEldestEntry` 做 LRU

### 问题 5：`injectSystemPrompt()` 的热替换逻辑在 Phase 5 后变成死代码

当前 `injectSystemPrompt()` 有一段逻辑：如果 ChatMemory 已有 SystemMessage 且内容变化，则替换它（clear + re-add）。Phase 5 将 SystemMessage 移出 ChatMemory 后，这段逻辑不再需要。

当前计划没有标注此清理。建议 Phase 5 Task 5.2 中一并移除或标记为 deprecated，避免维护者困惑。

### 问题 6：Speaker 标记在压缩摘要中出现冗余格式

`MemoryCompressor.summarize()` 对 UserMessage 输出 `"用户：[speaker=user_a] 打开空调"`，格式冗余（`用户：` + `[speaker=user_a]`）。压缩后的摘要可能包含冗余文本。

这是一个表现问题，不影响功能，但可考虑在压缩前从 UserMessage 文本移除 `[speaker=...]` 前缀。

---

## 四、确认无误的设计

| 设计点 | 结论 |
|--------|------|
| 短期 memory key = `sessionId`，不含 userId/personaId | ✅ 符合共享目标 |
| `SessionMemoryIds.shortTermMemoryId(sessionId)` | ✅ |
| `SessionMemoryStore.buildMemoryId()` 统一调用 `shortTermMemoryId` | ✅ |
| `SpeakerMessageFormatter` 独立值类型，可测试 | ✅ |
| 长期记忆提取使用原始用户文本（无 speaker 前缀） | ✅ |
| 长期记忆按 userId 读写 | ✅ |
| Persona 不参与 memory key，只影响 prompt 选择 | ✅ |
| Phase 1 纯函数测试先锁定规则 | ✅ |
| SessionMemoryStore 继续实现 ChatMemoryStore | ✅ |
| `SessionChatMemoryProvider` 封装 LangChain4j 细节 | ✅ |
| Fake ChatMemoryStore 用于 JVM 单测 | ✅ |
| AgentLoop 改动限制为三类（session 取 ChatMemory、speaker 标记、不走持久化） | ✅ |
| 缺失 sessionId 走 active session（不静默写 default） | ✅ |
| Phase 2 DB Version 升至 3，不做旧数据自动迁移 | ✅ |
| CleanMemory 修正 sessionId/userId 混淆 | ✅ |
| 自审清单中 4 项检查全部覆盖 | ✅ |

---

## 五、审查结论

**有条件通过。** 实施前必须修正以下 2 个阻塞问题：

| 优先级 | 问题 | 处理建议 |
|--------|------|---------|
| **阻塞** | Phase 4->Phase 5 的时序导致长记忆数据泄漏 | SystemMessage 去持久化提前到 Phase 4，与 ChatMemory session-scoped 改造同步完成 |
| **阻塞** | "预留 Context 接口"未明确定义 | 在 Phase 3 或 Phase 5 中明确接口签名（只读 messages？带预算的摘要？） |

非阻塞问题（3~6）建议在对应 Phase 中顺带修复。
