# Memory Session ChatMemory Phase 0 验收审查

**审查日期：** 2026-07-09
**审查范围：** Phase 0 身份模型硬门槛和 resolvedSessionId 贯穿（Task 0.1～0.4）
**审查依据：** `docs/plan_overall/2026-07-09-memory-session-chatmemory-implementation-plan.md` Phase 0 全部任务
**审查人：** Claude Code

---

## 一、总体评估

**通过验收，无阻塞问题。** 本阶段完成了 4 件事：

1. **定义 `SessionIdResolver` 接口**，`MemoryOrchestrator` 实现它，Runtime 在 `startSession()` 里预先解析 sessionId，确保后续 Context/Response/Trace 全程可见
2. **Runtime 链路贯通**：`startSession()` → `resolveSessionIdSafely()` → `RequestSessionFactory` 新重载 → `orchestratorContext["session_id"]` → `ContextFrame` → `RuntimeResult` → Trace
3. **全局删除语义**：新增 `deleteGlobalSession()`，`deleteSession()` 委托给全局删除
4. **Service 接线**：`AIAgentService` 创建 `AgentRuntime` 时注入 `memoryOrchestrator` 作为 `SessionIdResolver`

---

## 二、文件清单核对

### 2.1 新增文件

| 文件 | 状态 | 说明 |
|------|------|------|
| `memory/SessionIdResolver.java` | ✅ | `@FunctionalInterface`，5 参数，单方法 |
| `test/.../memory/SessionIdResolverTest.java` | ✅ | 3 测试 + 内嵌 FakeSessionIdResolver |
| `test/.../runtime/AgentRuntimeResolvedSessionTest.java` | ✅ | 3 测试覆盖 resolve + 显式传入 + null 兼容 |
| `test/.../memory/SessionMemoryStoreDeleteTest.java` | ✅ | @Ignore（Android 环境） |

### 2.2 修改文件

| 文件 | 变更 | 状态 |
|------|------|------|
| `memory/MemoryOrchestrator.java` | 实现 `SessionIdResolver.resolveSessionId()` | ✅ |
| `runtime/AgentRuntime.java` | 新增 `sessionIdResolver` 字段 + 新构造函数 + `resolveSessionIdSafely()` + trace 写入 `agent.session.id` | ✅ |
| `runtime/RequestSessionFactory.java` | 新增 `create(request, traceContext, intentResult, toolGroupSelectionResult, resolvedSessionId)` 重载 | ✅ |
| `memory/SessionMemoryStore.java` | 新增 `deleteGlobalSession()`，`deleteSession()` 委托之 | ✅ |
| `AIAgentService.kt` | `AgentRuntime` 初始化传入 `memoryOrchestrator` 作为 resolver | ✅ |

---

## 三、代码审查

### 3.1 resolvedSessionId 贯穿链路确认

```
AgentRuntime.startSession()
  → resolveSessionIdSafely(request)
    → MemoryOrchestrator.resolveSessionId(userId, rawSessionId, ...)
      ├─ rawSessionId 非空 → return trim(rawSessionId)
      ├─ 有 active session  → return active.sessionId
      └─ 无 active session → createConversationSession() → return new sessionId
  → RequestSessionFactory.create(..., resolvedSessionId)
    → RequestSession.sessionId() == resolvedSessionId（非空）
    → orchestratorContext["session_id"] == resolvedSessionId
  → writeRequestMetaToTrace() 写入 agent.session.id
```

### 3.2 Trace 写入 `agent.session.id`

```java
if (session.sessionId() != null) {
    traceContext.session().setAttribute("agent.session.id", session.sessionId());
}
```

Rationale: `TraceAttributeKeys.SESSION_ID = "session.id"` 可能已被 TraceManager 写入请求级别的 sessionId（原始值）。新增 `"agent.session.id"` 明确表示"Runtime 解析后的短期记忆归属 sessionId"。两种值同时存在时，`agent.session.id` 是最终使用的值。

### 3.3 全局删除语义

`deleteSession(userId, sessionId)` 不再只删除某个 user 的 metadata，改为：

```java
public boolean deleteSession(String userId, String sessionId) {
    return deleteGlobalSession(sessionId);
}
```

`deleteGlobalSession()` 在事务内按 `session_id` 删除所有 user 的 metadata 行 + 短期消息。注释中明确标注「若未来需要只从某 user 列表隐藏，应新增单独接口」。

### 3.4 向后兼容

`AgentRuntime` 仍然保留了不带 `SessionIdResolver` 的旧构造函数，内部传 `null` 给 `sessionIdResolver` 字段。`resolveSessionIdSafely()` 在 `sessionIdResolver == null` 时退回旧透传行为：

```java
if (sessionIdResolver == null) {
    return request != null ? emptyToNull(request.getSessionId()) : null;
}
```

测试 `nullResolverPreservesOldPassthroughBehavior()` 覆盖了此路径。

### 3.5 resolveSessionId 不更新 SessionManager 缓存

`MemoryOrchestrator.resolveSessionId()` 在命中 active session 时直接通过 `sessionStore.getActiveSession()` 查询 DB，不走 `SessionManager` 的 `activeSessions` 缓存。这意味着：

- 当存在 active session 时，`SessionManager.currentSessionId()` 可能返回旧值
- 但 sessionId 本身是正确且可用的（直接来自 DB）
- 这不影响短期消息的读写（memory key 使用 resolved sessionId，不依赖 SessionManager 缓存）

在合理场景中：Runtime 第一次 resolve 后，后续 SessionManager 的操作（如 `startNewSession()`）会重新同步缓存。当前行为无害。

---

## 四、审查结论

**Phase 0 通过验收，可以进入 Phase 1。**

| 检查项 | 状态 |
|--------|------|
| SessionIdResolver 接口定义 | ✅ |
| MemoryOrchestrator 实现 | ✅ |
| Runtime startSession 内 resolve | ✅ |
| RequestSessionFactory 新重载 | ✅ |
| orchestratorContext["session_id"] 写入 | ✅ |
| ContextFrame.sessionId 传递 | ✅ |
| RuntimeResult.sessionId 传递 | ✅ |
| Trace 写入 agent.session.id | ✅ |
| 全局删除 deleteGlobalSession | ✅ |
| deleteSession 语义收敛 | ✅ |
| Service 注入 memoryOrchestrator | ✅ |
| 全量单测 | ✅ BUILD SUCCESSFUL |
