# Memory Session ChatMemory Phase 0-6 验收审查报告

**审查日期：** 2026-07-09  
**审查对象：** `docs/act_summary/memory-session-chatmemory-summary.md` 所述 Phase 0-6 完成内容  
**审查方式：** 源码链路审查 + 计划对照 + 强制重跑 Gradle 验证  
**结论：** 不建议直接验收通过。编译和 JVM 单测可以通过，但当前实现仍存在会话身份链路和删除语义上的阻塞问题。

---

## 一、阻塞问题

### P0-1：显式 sessionId 请求会绕过用户会话指针维护，并可能额外创建幽灵 active session

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:59`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:63`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:175`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:179`

**问题说明：**

`resolveSessionId(...)` 在请求携带非空 `requestedSessionId` 时直接 `return requestedSessionId.trim()`，没有为当前 `userId` 建立或激活这个 session 的 metadata 指针。随后 `AgentLoopOrchestrator.buildSystemPromptMessage(...)` 调用 `MemoryOrchestrator.prepareSystemPrompt(userId, ...)`，而 `prepareSystemPrompt(...)` 会检查 `sessionManager.hasActiveSession(userId)`，若当前发言人没有 active session，就调用 `ctx.initSession()` 自动创建一个新 session。

这会导致以下问题：

1. 当前轮短期 ChatMemory 使用的是请求中的 `sessionId`。
2. 但当前 `userId` 的 active session 可能被偷偷创建成另一个新 session。
3. 后续同一用户如果省略 `sessionId`，Runtime 会解析到这个幽灵 active session，而不是刚才显式参与的座舱共享 session。
4. `listConversations(userId)` 也可能看不到用户刚参与的共享 session，反而看到自动创建的空会话。

**为什么是阻塞问题：**

本轮目标要求“TEXT 请求进入 Context 之前必须完成 resolvedSessionId 解析”，并且短期记忆以 `sessionId` 作为真实会话身份。当前实现让“显式 sessionId 短期消息”和“user -> active session 指针”分裂，后续切换会话、缺省 sessionId、会话列表都会出现不一致。

**建议修复：**

- `prepareSystemPrompt(...)` 不应再负责初始化 session。session 身份应只由 Runtime 前置解析链路决定。
- 对显式 `requestedSessionId`，需要明确策略：
  - 若该 session 已存在，给当前 `userId` 建立/激活 metadata 指针；
  - 若该 session 不存在，是创建 metadata，还是返回参数错误，需要产品侧确认；
  - 不能直接透传后又由 `prepareSystemPrompt(...)` 创建另一个无关 session。

---

### P0-2：删除会话只删 SQLite，不清理 live ChatMemory 缓存，删除后同 sessionId 仍可能读到旧短期历史

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:208`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:209`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java:87`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java:89`
- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java:90`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:217`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:219`

**问题说明：**

`SessionChatMemoryProvider.clear(sessionId)` 已经提供了同时清理 provider 缓存和底层 store 的能力，但 `MemoryOrchestrator.deleteSession(...)` 目前只调用 `sessionStore.deleteSession(userId, sessionId)`。如果某个 session 已经通过 `chatMemoryForSession(...)` 加载进 provider 缓存，调用 AIDL `deleteConversation(...)` 后，SQLite 记录会被删掉，但内存中的 `ChatMemory` 对象仍保留旧消息。

只要调用方继续传入同一个 `sessionId`，`SessionChatMemoryProvider.getOrCreate(sessionId, ...)` 会命中缓存并返回旧对象，导致“已删除会话”的历史再次进入模型上下文。

**为什么是阻塞问题：**

计划明确要求删除一个 `sessionId` 时删除该 session 的短期消息。当前实现只覆盖持久化层，没有覆盖正在运行的 live ChatMemory，长驻 Service 场景下会直接破坏删除语义。

**建议修复：**

- `MemoryOrchestrator.deleteSession(...)` 应通过 `sessionChatMemoryProvider.clear(sessionId)` 清理 live 缓存和 store，再删除 metadata；或新增 provider 的 `evictOnly(...)` 后由事务路径统一删除 store。
- 增加 provider 缓存已加载情况下的删除测试：先 `getOrCreate(sessionId).add(...)`，再 delete，最后再次 `getOrCreate(sessionId).messages()` 必须为空。

---

### P0-3：deleteConversation 对不存在的 sessionId 会返回成功

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:209`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:217`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:219`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:222`

**问题说明：**

`deleteGlobalSession(sessionId)` 使用 `execSQL("DELETE ...")` 删除消息和 metadata，随后直接 `return true`。`execSQL` 不返回删除行数，因此即使 `sessionId` 从未存在，也会返回成功。上层 `ConversationManager.deleteConversation(...)` 会把这个结果映射成删除成功，而不是 `ERROR_NOT_FOUND`。

**为什么是阻塞问题：**

会话 CRUD 是 AIDL 对外能力。删除不存在的会话返回成功，会让调用方误以为会话状态已被正确修改，也会掩盖会话身份解析错误。

**建议修复：**

- 删除前先查询 `sessions WHERE session_id=?` 或 `session_messages WHERE memory_id=?` 是否存在。
- 或改用 `SQLiteDatabase.delete(...)` 获取受影响行数，并以 metadata 或 message 删除行数判断是否真的存在。
- 补充不存在 session 删除返回 `false` 的单元/设备测试。

---

## 二、非阻塞但需要修正的问题

### P1-1：Phase 4 的关键 AgentLoop 测试是空测试，无法证明主路径没有走 fallback ChatMemory

**位置：**

- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java:40`
- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java:50`

**问题说明：**

`textPathFailsWhenSessionIdMissing()` 和 `textPathUsesSessionMemoryNotFallback()` 只有注释，没有断言，也没有构造被测对象。它们在 JUnit 中会被计为通过，但没有验证任何行为。

**影响：**

下属总结中“Phase 4 新增测试通过”的说法证据不足。尤其是“不走 fallback ChatMemory”“缺失 sessionId 返回 INVALID_CONFIG”属于本轮核心验收点，不能只保留骨架。

**建议修复：**

- 抽出可注入的 `PromptRenderer` 或构造轻量测试版 `PromptManager`，让 `AgentLoopOrchestrator` 可在 JVM 中真实运行。
- 如果短期内无法 JVM 化，应把这些测试移动到 androidTest，并在 testresult 中明确标记为“未验证”，不要计入已通过测试。

### P1-2：`MemoryPreProcessor` 注释说不能注入长期记忆，但实现仍会返回长期记忆 UserMessage

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`

**问题说明：**

文件注释写明 Phase 4 起长期记忆唯一注入点已收敛到 AgentLoop 的 transient SystemMessage，并强调该 preprocessor 不能再把长期记忆拼入用户消息。但当前 `prepare(...)` 仍会在存在长期记忆时返回 `UserMessage.from("【用户记忆参考】" + longTermCtx)`。

当前生产配置未发现 `new MemoryPreProcessor(...)` 调用，因此不是立即阻塞主路径的问题。但保留一个“注释说 no-op、实现仍注入”的 legacy 组件，会给后续维护和扩展留下误用风险。

**建议修复：**

- 如果它确实只作为 legacy 占位，应直接返回 `List.of()`，并在注释说明保留原因。
- 如果未来仍允许 legacy 路径使用它，应修正文档，不要称其为 no-op，并明确不能与 AgentLoop transient SystemMessage 同时使用。

### P2-1：`UserMemoryContext.endSession()` 仍使用全局 currentUserId，shutdown 多用户结束会话不可靠

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`
- `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java`

**问题说明：**

`MemoryOrchestrator.shutdown()` 遍历所有 `UserMemoryContext` 调用 `endSession()`，但 `UserMemoryContext.endSession()` 内部调用的是 `sessionManager.endSession()` 无参旧接口。该旧接口依赖 `SessionManager.currentUserId`，不一定等于正在遍历的 `UserMemoryContext.userId`。

**影响：**

多用户场景下，Service 关闭时可能只结束最后一次设置的 currentUserId 对应会话，其他用户 active session 未正确标记 inactive。该问题不影响当前轮消息写入，但会影响会话生命周期元数据。

**建议修复：**

- `UserMemoryContext.endSession()` 改为调用 `sessionManager.endSession(userId)`。
- 后续逐步移除 `SessionManager` 中依赖 `currentUserId` 的 deprecated 无参接口。

---

## 三、验证结果

本轮重新执行了以下命令：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，24 个任务实际执行。

```powershell
.\gradlew.bat assembleDebug --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，38 个任务实际执行。

注意：构建中存在 AndroidManifest 重复权限 warning，以及 `libc++_shared.so` 无法 strip 的提示；这些不是本轮 memory 改造引入的阻塞问题。

---

## 四、验收判断

当前实现已经完成了大部分结构性改造：`SessionChatMemoryProvider` 已存在，`SessionMemoryStore` 已实现 `ChatMemoryStore`，短期 memoryId 已收敛为 `sessionId`，Runtime 也已把 resolved sessionId 传入 `RequestSession` 和 `ContextFrame`。

但由于上述 P0 问题仍存在，本轮不能判定 Phase 0-6 已达到“可验收完成”状态。建议先修复会话身份指针、删除缓存一致性、删除不存在会话返回值三个问题，再补齐真实 AgentLoop 主路径测试或设备端验证。
