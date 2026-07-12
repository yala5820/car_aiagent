# Memory Session ChatMemory 修复后验收审查报告

**审查日期：** 2026-07-09  
**审查对象：** 下属针对 `memory-session-chatmemory-phase0-6-acceptance-review.md` 的修复结果  
**审查方式：** 源码链路复查 + 关键测试覆盖检查 + Gradle 验证尝试  
**结论：** 不能直接验收通过。上轮 3 个 P0 有对应修复动作，但本轮仍存在新的阻塞问题：非 TEXT 路径被 `session_id` 强校验误伤，且 caller extraContext 仍可覆盖 Runtime 解析出的 canonical sessionId。

---

## 一、上轮问题复查

### 已修复：`prepareSystemPrompt()` 不再创建幽灵 session

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:61`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:63`

当前 `prepareSystemPrompt(userId, baseSystemPrompt)` 只读取长期记忆，不再调用 `ctx.initSession()`。这修正了“显式 sessionId 请求后又额外创建 active session”的主要根因。

### 已部分修复：显式 sessionId 会建立当前 user 的 metadata 指针

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:173`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:185`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:187`

当前 `resolveSessionId(...)` 收到显式 `requestedSessionId` 后，会为当前 `userId` 创建或激活 metadata，并通过 `SessionManager.cacheSession(...)` 更新内存 active 指针。方向正确。

剩余风险：`sessionStore.createActiveSession(...)` 返回 `false` 时当前代码没有检查，仍会 `cacheSession(...)` 并返回该 sessionId。这会在 SQLite 写入失败时造成“内存 active 指针存在，但 metadata 不存在”的新分裂。建议补充返回值检查，失败时抛出明确异常或降级为创建失败。

### 已修复：`MemoryPreProcessor` 不再注入长期记忆 UserMessage

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`

当前 `prepare(...)` 固定返回 `List.of()`，与注释中的 legacy/no-op 语义一致。

### 已修复：`UserMemoryContext.endSession()` 按当前 userId 结束会话

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`

当前 `endSession()` 调用 `sessionManager.endSession(userId)`，不再依赖 `SessionManager.currentUserId`。

### 已部分修复：删除不存在 sessionId 不再返回成功

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:211`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:220`
- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:227`

`deleteGlobalSession(sessionId)` 现在先查 `sessions WHERE session_id = ?`，不存在时返回 `false`。这修正了上轮 P0。

---

## 二、阻塞问题

### P0-1：VOICE 和 scene 路径绕过 AgentRuntime，缺少 session_id，会被 AgentLoop 直接拒绝

**位置：**

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:129`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:131`
- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:134`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:164`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:738`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt:743`

**问题说明：**

`AgentLoopOrchestrator.execute(...)` 当前无条件要求 `extraContext["session_id"]` 非空，否则返回：

```text
resolved sessionId is required before AgentLoop execution
```

但 Service 中至少有两条路径没有通过 `AgentRuntime.startSession(...)` 获取 resolved sessionId：

1. scene 路径：`sceneOrchestrator.execute("", mapOf("scene" to scene))`
2. VOICE 路径：手动构造 `ctx`，只放入 `user_id`、`persona_id` 和 trace context，然后调用 `chatOrchestrator.execute(...)`

因此 memory 改造后，TEXT 主路径可走 Runtime，但 VOICE 和 scene 这两条既有路径会被 AgentLoop 的 session 强校验拦下。尤其 scene persona 的 `memoryPolicy` 是 `EPHEMERAL`，本不应强制依赖 session-scoped persistent ChatMemory。

**影响：**

- VOICE 请求会返回失败，而不是进入对话模型。
- scene 服务会返回“系统: 场景服务暂时不可用”，且真实原因被包装掉。
- 本轮 memory 改造外溢破坏了非 TEXT 运行路径。

**建议修复：**

- 对 `PERSISTENT` memory policy 才强制要求 resolved `session_id` 并走 `memoryOrchestrator.chatMemoryForSession(...)`。
- 对 `EPHEMERAL` / `NONE` persona 使用原有 `fallbackChatMemory` 或无记忆窗口，不应要求 sessionId。
- 或者把 VOICE 也统一迁移到 `AgentRuntime`，确保它和 TEXT 一样走 resolved sessionId 链路。
- 补充 VOICE/scene 的最小回归测试，至少验证没有 `session_id` 的 EPHEMERAL persona 不会返回 `INVALID_CONFIG`。

---

### P0-2：`request.extraContext` 可以覆盖 Runtime 解析出的 canonical `session_id`

**位置：**

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:86`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:92`
- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java:93`
- `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeResolvedSessionTest.java:43`

**问题说明：**

`RequestSessionFactory.create(...)` 当前先写入解析后的：

```java
context.put("session_id", sessionId);
```

随后又执行：

```java
context.putAll(request.getExtraContext());
```

这意味着调用方只要在 `AgentRequest.extraContext` 中携带 `session_id`，就能覆盖 Runtime 刚解析出的 canonical `resolvedSessionId`。最终会出现：

- `RequestSession.sessionId()` 是 resolved session；
- `orchestratorContext["session_id"]` 可能是 caller 传入的另一个 session；
- `ContextFrame.sessionId()` 仍可能是 resolved session；
- `AgentLoopOrchestrator` 实际读取的是被覆盖后的 `extraContext["session_id"]`。

这会直接破坏“Runtime 在 Context 构建前解析 sessionId，并贯穿 RequestSession / ContextFrame / AgentLoop”的核心目标。

**建议修复：**

- 先合并 caller extraContext，再写入 `user_id`、`session_id`、`persona_id`、`client_message_id` 等 canonical 字段。
- 或显式过滤 `extraContext` 中的保留 key：`session_id`、`user_id`、`persona_id`、`client_message_id`、trace/context 内部 key。
- 补充测试：当 request.extraContext 含 `session_id=evil-session` 时，`RequestSession.sessionId()`、`orchestratorContext["session_id"]`、`ContextFrame.sessionId()`、`RuntimeResult.sessionId()` 必须全部等于 resolver 返回值。

---

## 三、非阻塞但需要处理的问题

### P1-1：删除会话的 cache/store 清理仍不是原子语义

**位置：**

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:224`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:229`
- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java:230`

**问题说明：**

`MemoryOrchestrator.deleteSession(...)` 先执行 `sessionChatMemoryProvider.clear(sessionId)`，该方法会删除 provider cache 和 `session_messages`；随后才调用 `sessionStore.deleteSession(...)` 删除 metadata。

如果后续 metadata 删除失败，此方法会返回 `false`，但短期消息和 live cache 已经被清掉，操作结果和数据状态不一致。

**建议修复：**

- 先在 store 层确认 session 存在。
- 将 provider 分为 `evictOnly(sessionId)` 和 `deleteMessages(sessionId)` 两个动作。
- 最好由 store 完成数据库事务删除，事务成功后再 evict provider cache。

### P1-2：关键修复仍缺少可执行测试

**位置：**

- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`
- `app/src/test/java/com/hirain/aiagent/memory/SessionMemoryStoreDeleteTest.java`

**问题说明：**

以下关键验收点仍没有真实可执行测试：

- `textPathFailsWhenSessionIdMissing()` 仍是空测试。
- `textPathUsesSessionMemoryNotFallback()` 仍是空测试。
- `SessionMemoryStoreDeleteTest` 仍被 `@Ignore`。
- 没有测试覆盖 VOICE/scene 绕过 Runtime 时是否会被 session 强校验误伤。
- 没有测试覆盖 `request.extraContext` 覆盖 canonical `session_id` 的场景。
- 没有测试覆盖 provider cache 已加载后 `deleteConversation(...)` 清理 live ChatMemory 的行为。

这些测试缺口会让后续修改再次破坏 memory 主路径而不被 JVM 单测发现。

---

## 四、验证结果

本轮尝试执行：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

结果：未执行成功。沙箱内失败原因：

```text
java.io.FileNotFoundException: C:\Users\yala5\.gradle\wrapper\dists\gradle-8.11.1-bin\...\gradle-8.11.1-bin.zip.lck (拒绝访问。)
```

尝试申请提权执行时，系统因当前用量限制拒绝审批，因此本轮无法重新证明单元测试通过。

本轮也尝试执行：

```powershell
.\gradlew.bat assembleDebug --rerun-tasks
```

结果：同样因上述 Gradle wrapper lock 文件权限失败，未能重新构建。

---

## 五、验收判断

上轮指出的若干点已经被修复，尤其是 `prepareSystemPrompt()` 不再创建 session、`MemoryPreProcessor` no-op、删除不存在 session 不再成功。

但当前仍不能验收通过，原因是：

1. `AgentLoopOrchestrator` 的 session 强校验影响了 VOICE 和 scene 既有路径。
2. `RequestSessionFactory` 允许 caller extraContext 覆盖 canonical `session_id`，破坏 resolved sessionId 的可信链路。
3. 删除语义和关键路径测试仍存在明显缺口。

建议先修复两个 P0，再补充对应回归测试；之后重新跑 `testDebugUnitTest --rerun-tasks` 和 `assembleDebug --rerun-tasks`，再进入下一轮验收。
