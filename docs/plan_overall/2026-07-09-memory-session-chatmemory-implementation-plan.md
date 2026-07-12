# Memory Session ChatMemory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AIAgent 的短期记忆主路径改造成 `sessionId` 级共享上下文，底层使用 LangChain4j `ChatMemory` + AIAgent 自研 `SessionMemoryStore`，并为后续 Context 模块接管本轮 prompt 组装预留干净接口。

**Architecture:** Memory 模块继续负责短期/长期记忆的存储、读取、压缩和提取；短期记忆归属 `sessionId`，长期记忆归属当前发言人 `userId`，`personaId` 不参与任何 memory key。`AgentLoopOrchestrator` 只做极窄接线：每轮根据请求上下文取得当前 `ChatMemory`，不在本轮引入完整 Context prompt 组装策略。

**Tech Stack:** Android Service, Java/Kotlin, LangChain4j `ChatMemory` / `ChatMemoryStore` / `MessageWindowChatMemory`, SQLite, JUnit, Gradle 8 / AGP 8.9.1.

---

## 0. 工作边界和设计结论

### 本轮必须完成

- 短期记忆主路径从固定 `"ChatMemory"` 改为按 `sessionId` 选择真实 `ChatMemory`。
- `SessionMemoryStore` 成为 TEXT 短期记忆唯一持久化底座，替代 `PersistentChatMemorySqlite(context, "ChatMemory")` 的主路径用途。
- 短期 memory id 只使用 `sessionId`；同一会话内不同 `userId`、不同 `personaId` 共享短期上下文。
- `sessionId` 是全局共享会话 id，不是 user-local id；`userId -> active session` 只是当前发言人的会话指针，不代表短期消息所有权。
- TEXT 请求进入 Context 之前必须完成 `resolvedSessionId` 解析，并贯穿 `RequestSession`、`orchestratorContext`、`ContextFrame`、`RuntimeResult`、`AgentResponse` 和 trace。
- 长期记忆继续按 `userId` 读取和写入；长期记忆提取使用当前请求的发言人 `userId`。
- 短期历史中保留发言人标记，避免多人共享会话时模型无法判断“我”指代谁。
- 压缩写回必须通过 `SessionChatMemoryProvider` 同步 live `ChatMemory` 和底层 store，不能只直接写 `SessionMemoryStore`。
- 会话删除语义固定为全局共享会话删除：删除一个 `sessionId` 时，应删除所有 user 的相关 metadata 行和该 session 的短期消息；如未来需要“只删除某 user 列表入口”，另开 pointer-only 删除接口。
- 长期记忆只作为本轮 transient/system 上下文进入模型输入，不允许持久化进共享短期 ChatMemory。
- 预留 Context 后续接管所需的只读 memory 接口：按 `sessionId` 读取短期消息快照和现有压缩摘要；本轮不让 Context 决定 prompt。
- 增加单元测试覆盖 memoryId、speaker 标记、provider 选择、AgentLoop 窄接线行为。

### 本轮明确不做

- 不让 Context 模块全面接管本轮 prompt 组装。
- 不让 `MemoryContextProvider` 渲染完整短期上下文。
- 不把 Memory 模块改成 prompt policy owner。
- 不在本轮实现带预算的 Context memory 裁剪策略；只暴露只读快照接口，预算选择留给 Context 阶段。
- 不迁移到 Room。
- 不自研替代 LangChain4j `ChatMemory` 的完整实现。
- 不一次性切换到 `TokenWindowChatMemory`；本轮继续使用 `MessageWindowChatMemory`，但通过 provider 隔离构造逻辑，保留后续替换窗口策略的入口。

### 开工前确认项

计划固定采用以下策略：当 TEXT 请求缺少 `sessionId` 时，Runtime 在构建 Context 前通过 Memory 模块解析当前 active session；若当前用户没有 active session，则创建一个新 session，并把该 `resolvedSessionId` 作为本轮短期记忆 key。不得静默写入 `"default_session"`，也不得落回固定 fallback `ChatMemory`。

如果产品后续改成“缺少 `sessionId` 时直接返回参数错误”，应在执行 Phase 0 前整体调整 `SessionIdResolver`、`AgentRuntime.startSession(...)` 和对应测试；本计划不同时保留两套分支。

---

## 1. 文件结构和职责规划

### 新增文件

- `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`  
  负责按 `sessionId` 获取或创建 LangChain4j `ChatMemory`。它封装 `MessageWindowChatMemory.builder().chatMemoryStore(sessionStore).id(sessionId)` 这一类 LangChain4j 细节，避免 `AgentLoopOrchestrator` 直接操作 store id；同时提供有上限的 LRU 缓存，避免长期运行 Service 中缓存无限增长。

- `app/src/main/java/com/hirain/aiagent/memory/SessionIdResolver.java`  
  Runtime 层用于解析 `resolvedSessionId` 的窄接口。设计原因：Runtime 必须在 Context 构建前拿到真实 sessionId，但不应直接操作 session 数据表；接口由 `MemoryOrchestrator` 实现。

- `app/src/main/java/com/hirain/aiagent/memory/MemorySnapshot.java`  
  Context 预留用只读快照值类型，包含 `sessionId`、短期消息列表、消息数、估算 token 和可选摘要。Memory 模块只返回数据，不决定本轮 prompt 如何使用这些数据。

- `app/src/main/java/com/hirain/aiagent/memory/SpeakerMessageFormatter.java`  
  负责把当前发言人 `userId` 写入短期历史文本，例如 `[speaker=user_a] 打开空调`。长期记忆提取仍使用原始用户文本，不使用带 speaker 前缀的文本。

- `app/src/test/java/com/hirain/aiagent/memory/SessionMemoryIdsTest.java`  
  覆盖短期 memory id 只由 `sessionId` 决定。

- `app/src/test/java/com/hirain/aiagent/memory/SpeakerMessageFormatterTest.java`  
  覆盖 speaker 标记格式、空 userId 降级、原始文本不被破坏。

- `app/src/test/java/com/hirain/aiagent/memory/SessionChatMemoryProviderTest.java`  
  使用 fake `ChatMemoryStore` 覆盖同 session 复用、不同 session 隔离、超过缓存上限时淘汰最久未访问 session、压缩替换后 live `ChatMemory` 立即可见。

- `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeResolvedSessionTest.java`  
  覆盖缺失 `sessionId` 时 Runtime 在 Context 构建前解析出 `resolvedSessionId`，并写入 `RequestSession`、`ContextFrame`、`RuntimeResult` / `AgentResponse` 可见链路。

- `app/src/test/java/com/hirain/aiagent/memory/MemorySnapshotTest.java`  
  覆盖 `MemorySnapshot` 不可变性和只读语义。

- `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`  
  使用 fake provider / fake model 验证 AgentLoop 每轮使用对应 session 的 `ChatMemory`。

### 修改文件

- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryIds.java`  
  将短期 key 规则收敛为 `shortTermMemoryId(sessionId) = normalizedSessionId`；保留用于删除会话的 prefix helper，但不再把 `userId` 或 `personaId` 放入短期 key。

- `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`  
  继续实现 LangChain4j `ChatMemoryStore`，但 `session_messages.memory_id` 语义改为 `sessionId`。会话元数据表仍保留 `user_id`，因为 active session 和列表查询仍是用户维度的管理能力；全局删除 session 时必须按 `session_id` 删除所有 user metadata 行。

- `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java`  
  保留 user -> active session 管理；`currentMemoryId(userId)` 返回当前 active `sessionId`，不再返回 `userId_sessionId`。

- `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`  
  长期记忆逻辑保持 `userId` 维度；短期压缩写回时使用当前 sessionId。

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`  
  实现 `SessionIdResolver`，暴露 `resolveSessionId(...)`、`chatMemoryForSession(sessionId, maxMessages)`、`readSessionMessages(sessionId)`、`getMemorySnapshot(sessionId)`、`getSummarizedMemory(sessionId, maxChars)`，提供 session 解析、短期消息读写、压缩写回入口。`onTurnComplete(...)` 增加 `sessionId` 参数，压缩写回委托 provider 替换 live `ChatMemory`，避免缓存与 store 分裂。

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`  
  极窄改动：每轮 `execute(...)` 从 `extraContext` 读取已解析的 `session_id`，先把 SystemMessage 改成本轮 transient 消息，再通过 Memory 模块取得当前 `ChatMemory`，并使用带 speaker 标记的 `UserMessage` 写入短期历史。TEXT 主路径不得使用固定 fallback `ChatMemory`。

- `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`  
  在 `startSession(...)` 中通过 `SessionIdResolver` 解析 `resolvedSessionId`，再构建 `RequestSession` 和 Context。Runtime 只负责身份贯穿，不直接读写 memory store。

- `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`  
  增加接收 `resolvedSessionId` 的创建入口，确保 `RequestSession.sessionId()` 和 `orchestratorContext["session_id"]` 都使用解析后的非空 sessionId。

- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`  
  停止为 TEXT 主路径硬编码 `.chatMemoryStoreId("ChatMemory")` 的实际作用。保留字段兼容时，应在注释中标明它不再决定 session-scoped memory。

- `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java`  
  若实现中保留 `chatMemoryStoreId`，不扩展语义；若删除会引起大量调用点变化，本轮不删除，只弱化为兼容字段。

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`  
  创建 `AgentRuntime` 时注入 `memoryOrchestrator` 作为 `SessionIdResolver`；同时只允许修正与 memory 清理直接相关的错误调用，例如 `ClearChatMemory` 不得把 `request.sessionId` 当作 `userId` 传给 `memoryOrchestrator.startNewSession(...)`。

- `docs/overview/memory-module-overview.md`  
  更新当前 memory 模块说明，明确短期 session-scoped、长期 user-scoped、Context 接管 prompt 是下一阶段工作。

---

## 2. Phase 0: 身份模型硬门槛和 resolvedSessionId 贯穿

**目标：** 在任何 ChatMemory 主路径改造前，先固定 session 身份模型：`sessionId` 是全局共享会话 id；缺失 sessionId 必须在 Runtime 进入 Context 前解析为 `resolvedSessionId`；删除语义必须与全局共享短期消息一致。

### Task 0.1: 定义 SessionIdResolver 窄接口

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionIdResolver.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/SessionIdResolverTest.java`

- [ ] **Step 1: 写失败测试**

测试目标：缺失 `sessionId` 时返回当前 active session；没有 active session 时创建新 session；显式传入 sessionId 时直接返回规范化结果。

```java
package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;

public class SessionIdResolverTest {
    @Test
    public void explicitSessionIdWins() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver("active-1");

        assertEquals("request-session",
                resolver.resolveSessionId("user_a", " request-session ", "标题", "chat", "launcher"));
    }

    @Test
    public void missingSessionUsesActiveSession() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver("active-1");

        assertEquals("active-1",
                resolver.resolveSessionId("user_a", null, "标题", "chat", "launcher"));
    }

    @Test
    public void missingSessionCreatesSessionWhenNoActiveSession() {
        FakeSessionIdResolver resolver = new FakeSessionIdResolver(null);

        String resolved = resolver.resolveSessionId("user_a", null, "标题", "chat", "launcher");

        assertNotNull(resolved);
        assertEquals(resolved, resolver.createdSessionId());
    }
}
```

`FakeSessionIdResolver` 可放在测试类内部，用来表达接口语义；生产实现由 `MemoryOrchestrator` 承担。

- [ ] **Step 2: 新增接口**

```java
package com.hirain.aiagent.memory;

/**
 * Runtime 解析本轮真实 sessionId 的窄接口。
 * 设计原因：Context 构建和响应映射都必须看到 resolvedSessionId，
 * 但 Runtime 不应直接操作 SessionMemoryStore 的表结构。
 */
public interface SessionIdResolver {
    String resolveSessionId(String userId, String requestedSessionId,
                            String title, String personaId, String sourceApp);
}
```

- [ ] **Step 3: `MemoryOrchestrator` 实现接口**

```java
public class MemoryOrchestrator implements SessionIdResolver {
    @Override
    public String resolveSessionId(String userId, String requestedSessionId,
                                   String title, String personaId, String sourceApp) {
        String normalizedUserId = isBlank(userId) ? "default_user" : userId.trim();
        if (!isBlank(requestedSessionId)) {
            return requestedSessionId.trim();
        }
        SessionMemoryStore.SessionInfo active = sessionStore.getActiveSession(normalizedUserId);
        if (active != null && !isBlank(active.sessionId)) {
            return active.sessionId;
        }
        return createConversationSession(normalizedUserId, title, personaId, sourceApp);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
```

实现时应复用已有 `SessionManager` / `SessionMemoryStore`，不要在 Runtime 中复制 session 表逻辑。

### Task 0.2: 让 resolvedSessionId 进入 RequestSession / Context / Response / Trace

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java` only if constructor access prevents test setup
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeResolvedSessionTest.java`

- [ ] **Step 1: 写失败测试**

测试目标：请求不带 `sessionId`，Runtime 仍在执行前把 resolved session 写入 `RequestSession` 和 Context，最终 `RuntimeResult.sessionId()` 非空。

```java
@Test
public void missingRequestSessionIdIsResolvedBeforeContextBuild() {
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setUserId("user_a");
    request.setText("打开空调");

    RecordingExecutor executor = new RecordingExecutor();
    FixedSessionIdResolver resolver = new FixedSessionIdResolver("resolved-session-1");
    AgentRuntime runtime = new AgentRuntime(
            executor,
            ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                    .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                    .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                    .build()),
            resolver,
            new FixedIdGenerator("request-1"),
            new FixedTimeProvider(1000L));

    RequestSession session = runtime.startSession(request, null);
    RuntimeResult result = runtime.execute(session);

    assertEquals("resolved-session-1", session.sessionId());
    assertEquals("resolved-session-1", session.orchestratorContext().get("session_id"));
    assertEquals("resolved-session-1", executor.lastContextFrame().sessionId());
    assertEquals("resolved-session-1", result.sessionId());
}
```

- [ ] **Step 2: 修改 Runtime 构造注入**

新增字段：

```java
private final SessionIdResolver sessionIdResolver;
```

生产构造从 `AIAgentService` 传入 `memoryOrchestrator`；测试构造可传入 fake resolver。无 memory 的测试路径可使用严格 fake，不要默认返回 null。

已有 `AgentRuntimeTest` / `AgentRuntimeContextTest` 中，如果请求没有设置 `sessionId`，应显式传入：

```java
SessionIdResolver resolver = (userId, requestedSessionId, title, personaId, sourceApp) ->
        requestedSessionId != null ? requestedSessionId : "test-session";
AgentRuntime runtime = new AgentRuntime(executor, contextOrchestrator,
        resolver, () -> "req-1", () -> 1000L);
```

不要在生产默认构造里内置 `"test-session"` 之类的兜底值；生产默认构造必须由 `AIAgentService` 注入真实 `MemoryOrchestrator`。

- [ ] **Step 3: 在 `startSession(...)` 中先 resolve 再创建 RequestSession**

```java
String userId = request != null && request.getUserId() != null && !request.getUserId().isEmpty()
        ? request.getUserId()
        : "default_user";
String requestedSessionId = request != null ? request.getSessionId() : null;
String personaId = request != null && request.getPersonaId() != null && !request.getPersonaId().isEmpty()
        ? request.getPersonaId()
        : "chat";
String sourceApp = request != null && request.getSourceApp() != null && !request.getSourceApp().isEmpty()
        ? request.getSourceApp()
        : "unknown";
String title = request != null && request.getText() != null ? request.getText() : "";
String resolvedSessionId = sessionIdResolver.resolveSessionId(
        userId, requestedSessionId, title, personaId, sourceApp);

RequestSession session = sessionFactory.create(request, traceContext,
        intentResult, toolGroupSelectionResult, resolvedSessionId);
```

- [ ] **Step 4: 修改 `RequestSessionFactory`**

新增重载：

```java
public RequestSession create(AgentRequest request,
                             TraceContext traceContext,
                             IntentResult intentResult,
                             ToolGroupSelectionResult toolGroupSelectionResult,
                             String resolvedSessionId) {
    // 原有规范化逻辑保持不变，但 sessionId 使用 resolvedSessionId。
    String sessionId = nonEmpty(resolvedSessionId, emptyToNull(request.getSessionId()));
    Map<String, Object> context = new HashMap<>();
    context.put("user_id", userId);
    context.put("session_id", sessionId);
    context.put("persona_id", normalizedPersonaId);
    ...
    return new RequestSession(request, requestId, sessionId, userId, ...);
}
```

旧 `create(...)` 可保留给兼容测试，但生产 `AgentRuntime.startSession(...)` 必须调用新重载。

- [ ] **Step 5: 写入 Trace**

在 `AgentRuntime.writeRequestMetaToTrace(...)` 中补充：

```java
if (session.sessionId() != null) {
    traceContext.session().setAttribute("agent.session.id", session.sessionId());
}
```

Expected：Trace root span 可看到本轮真实短期记忆归属。

- [ ] **Step 6: 在 Service 装配 Runtime 时传入 resolver**

`AIAgentService` 当前使用 `AgentRuntime(AgentExecutor, contextOrchestrator, RuntimeCancelChecker)`。新增生产重载：

```java
public AgentRuntime(AgentExecutor chatExecutor,
                    ContextOrchestrator contextOrchestrator,
                    SessionIdResolver sessionIdResolver,
                    RuntimeCancelChecker cancelChecker) {
    this(chatExecutor, contextOrchestrator, new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            new UuidIdGenerator(), new SystemTimeProvider(),
            sessionIdResolver, cancelChecker);
}
```

然后在 `AIAgentService` 中改为：

```kotlin
agentRuntime = AgentRuntime(
    AgentExecutor { userInput, context ->
        textOrchestrator.execute(userInput, context)
    },
    contextOrchestrator,
    memoryOrchestrator,
    RuntimeCancelChecker { runtimeSession ->
        activeRequestRegistry.get(runtimeSession.requestId())?.isCancelled == true
    }
)
```

Runtime 使用的 `SessionIdResolver` 必须和 AgentLoop/Memory 使用的是同一个 `memoryOrchestrator` 实例，避免 resolve 到一个 session、写入另一个 memory store。

### Task 0.3: 固定共享 session 的 metadata / delete 语义

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java` if delete wrapper exists
- Test: `app/src/test/java/com/hirain/aiagent/memory/SessionMemoryStoreDeleteTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
public void globalDeleteRemovesAllMetadataRowsAndSharedMessages() {
    SessionMemoryStore store = newInMemoryStore();
    store.createActiveSession("user_a", "shared-session", "A", "chat", "launcher");
    store.createActiveSession("user_b", "shared-session", "B", "chat", "launcher");
    store.updateMessages("shared-session", List.of(UserMessage.from("hello")));

    assertTrue(store.deleteGlobalSession("shared-session"));

    assertNull(store.getSession("user_a", "shared-session"));
    assertNull(store.getSession("user_b", "shared-session"));
    assertTrue(store.getMessages("shared-session").isEmpty());
}
```

如果 JVM 环境无法直接跑 Android SQLite，保留该测试为 instrumentation 测试，并在 Phase 6 的设备验证中运行。

- [ ] **Step 2: 新增全局删除方法**

```java
public boolean deleteGlobalSession(String sessionId) {
    String normalizedSessionId = SessionMemoryIds.shortTermMemoryId(sessionId);
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    db.beginTransaction();
    try {
        db.execSQL("DELETE FROM session_messages WHERE memory_id = ?",
                new Object[]{normalizedSessionId});
        db.execSQL("DELETE FROM sessions WHERE session_id = ?",
                new Object[]{normalizedSessionId});
        db.setTransactionSuccessful();
        return true;
    } finally {
        db.endTransaction();
    }
}
```

- [ ] **Step 3: 将 `deleteSession(userId, sessionId)` 语义收敛为全局删除**

```java
public boolean deleteSession(String userId, String sessionId) {
    return deleteGlobalSession(sessionId);
}
```

中文注释必须说明：本轮将 `deleteConversation(userId, sessionId)` 定义为删除整个共享 session；`userId` 仅用于权限/调用方兼容，不用于限制短期消息删除范围。若未来需要“只从某 user 的列表隐藏”，应新增单独接口，不复用 deleteConversation。

### Task 0.4: 禁止 TEXT 正常路径使用 fallback ChatMemory

**Files:**
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`
- Modify later in Phase 4: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`

- [ ] **Step 1: 写计划约束测试**

该测试在 Phase 4 实现时落地：当 `extraContext` 已含 resolved `session_id`，AgentLoop 必须调用 `memoryOrchestrator.chatMemoryForSession(sessionId, maxMessages)`；如果缺失 `session_id`，TEXT 主路径应返回明确错误，不能访问 `FallbackChatMemory`，也不能在 AgentLoop 内再次解析 session。

核心断言：

```java
assertFalse(fakeFallbackChatMemory.wasRead());
assertEquals("resolved-session-1", fakeMemoryOrchestrator.lastRequestedSessionId());
```

- [ ] **Step 2: 执行顺序约束**

Phase 4 不再允许如下代码作为 TEXT 主路径：

```java
ChatMemory chatMemory = memoryOrchestrator != null && sessionId != null
        ? memoryOrchestrator.chatMemoryForSession(sessionId)
        : fallbackChatMemory;
```

正确策略是：Runtime 保证 `session_id` 非空，AgentLoop 只在 legacy / 非 TEXT / 明确无记忆策略下使用 fallback。

---

## 3. Phase 1: 定义短期记忆身份模型和纯函数测试

**目标：** 先用纯 Java 单测锁定 memory key 和 speaker 标记规则，避免后续实现阶段在 `userId/sessionId/personaId` 之间反复摇摆。

### Task 1.1: 收敛 `SessionMemoryIds`

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryIds.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/SessionMemoryIdsTest.java`

- [ ] **Step 1: 写失败测试**

新增测试类，先表达目标行为：

```java
package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SessionMemoryIdsTest {
    @Test
    public void shortTermMemoryIdUsesOnlySessionId() {
        assertEquals("session-1", SessionMemoryIds.shortTermMemoryId("session-1"));
    }

    @Test
    public void blankSessionIsRejectedBecauseRuntimeMustResolveIt() {
        try {
            SessionMemoryIds.shortTermMemoryId("");
            fail("blank sessionId should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("sessionId must be resolved before building short-term memory id",
                    expected.getMessage());
        }
        try {
            SessionMemoryIds.shortTermMemoryId(null);
            fail("null sessionId should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("sessionId must be resolved before building short-term memory id",
                    expected.getMessage());
        }
    }

    @Test
    public void userAndPersonaDoNotParticipateInShortTermKey() {
        String first = SessionMemoryIds.shortTermMemoryId("session-1");
        String second = SessionMemoryIds.shortTermMemoryId("session-1");
        assertEquals(first, second);
    }

    @Test
    public void deletePrefixMatchesOnlyOneSessionId() {
        assertEquals("session-1", SessionMemoryIds.shortTermMemoryPrefix("session-1"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SessionMemoryIdsTest"
```

Expected: FAIL，原因是 `shortTermMemoryId(...)` 和 `shortTermMemoryPrefix(...)` 尚不存在。

- [ ] **Step 3: 实现最小代码**

调整 `SessionMemoryIds`，保留旧方法但标记为兼容路径，新增短期 key 方法：

```java
package com.hirain.aiagent.memory;

public final class SessionMemoryIds {
    private SessionMemoryIds() {}

    public static String shortTermMemoryId(String sessionId) {
        if (isBlank(sessionId)) {
            throw new IllegalArgumentException(
                    "sessionId must be resolved before building short-term memory id");
        }
        return sessionId.trim();
    }

    public static String shortTermMemoryPrefix(String sessionId) {
        return shortTermMemoryId(sessionId);
    }

    /** 兼容旧调用；短期记忆不再使用 userId/personaId 参与 key。 */
    @Deprecated
    public static String build(String userId, String sessionId, String personaId) {
        return shortTermMemoryId(sessionId);
    }

    /** 兼容旧删除逻辑；调用方应传入 sessionId。 */
    @Deprecated
    public static String buildPrefix(String userId, String sessionId) {
        return shortTermMemoryPrefix(sessionId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SessionMemoryIdsTest"
```

Expected: PASS。

### Task 1.2: 增加 speaker 标记格式化器

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/memory/SpeakerMessageFormatter.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/SpeakerMessageFormatterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.hirain.aiagent.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeakerMessageFormatterTest {
    @Test
    public void formatsUserMessageWithSpeakerId() {
        assertEquals("[speaker=user_a] 打开空调",
                SpeakerMessageFormatter.formatUserMessage("user_a", "打开空调"));
    }

    @Test
    public void blankSpeakerFallsBackToDefaultUser() {
        assertEquals("[speaker=default_user] 你好",
                SpeakerMessageFormatter.formatUserMessage("", "你好"));
    }

    @Test
    public void nullTextBecomesEmptySpeakerLine() {
        assertEquals("[speaker=user_a] ",
                SpeakerMessageFormatter.formatUserMessage("user_a", null));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SpeakerMessageFormatterTest"
```

Expected: FAIL，原因是 `SpeakerMessageFormatter` 尚不存在。

- [ ] **Step 3: 实现格式化器**

```java
package com.hirain.aiagent.memory;

/**
 * 共享短期会话中的发言人标记工具。
 * 设计原因：座舱内同一个 session 可能有多个发言人，短期历史必须保留 speaker，
 * 否则模型无法判断历史消息中的“我”属于哪个用户。
 */
public final class SpeakerMessageFormatter {
    private static final String DEFAULT_USER_ID = "default_user";

    private SpeakerMessageFormatter() {}

    public static String formatUserMessage(String userId, String text) {
        String safeUserId = isBlank(userId) ? DEFAULT_USER_ID : userId.trim();
        String safeText = text != null ? text : "";
        return "[speaker=" + safeUserId + "] " + safeText;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
```

- [ ] **Step 4: 运行 Phase 1 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SessionMemoryIdsTest" --tests "com.hirain.aiagent.memory.SpeakerMessageFormatterTest"
```

Expected: PASS。

- [ ] **Step 5: 阶段验收**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
```

Expected: memory 包现有测试和新增测试均 PASS。

---

## 4. Phase 2: 让 `SessionMemoryStore` 成为 LangChain4j 短期存储底座

**目标：** `SessionMemoryStore` 继续实现 LangChain4j `ChatMemoryStore`，但 `memoryId` 语义改为 `sessionId`。会话管理仍可按 `userId` 查询 active session，短期消息存储不再按用户分裂；删除会话采用 Phase 0 固定的全局共享 session 删除语义。

### Task 2.1: 调整 `SessionMemoryStore` 的 memory id 语义

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java`

- [ ] **Step 1: 修改 `SessionMemoryStore.buildMemoryId(...)`**

将方法改成只返回 `sessionId`：

```java
/** 构建符合 ChatMemoryStore 约定的短期 memoryId。短期记忆只按 sessionId 隔离。 */
public static String buildMemoryId(String userId, String sessionId) {
    return SessionMemoryIds.shortTermMemoryId(sessionId);
}
```

- [ ] **Step 2: 修改 `deleteSession(...)` 删除逻辑**

当前删除逻辑使用 `LIKE prefix%`，并只删除当前 user 的 metadata。短期 key 收敛为全局 `sessionId` 后，应委托 Phase 0 的全局删除方法：

```java
public boolean deleteSession(String userId, String sessionId) {
    return deleteGlobalSession(sessionId);
}
```

不要保留“删除当前 user metadata + 删除全局 session_messages”的组合；这会导致其他 user 仍能 list/switch 到一个已经没有短期消息的 session。

- [ ] **Step 3: 修改 `currentMemoryId(...)`**

确保 `currentMemoryId(userId)` 返回 active session 对应的 `sessionId`：

```java
public String currentMemoryId(String userId) {
    SessionInfo session = getActiveSession(userId);
    return session != null ? buildMemoryId(userId, session.sessionId) : null;
}
```

该方法签名可保留，便于上层兼容；语义变为“当前用户 active session 的短期 memory id”。

- [ ] **Step 4: 修改 `SessionManager.currentMemoryId(...)`**

```java
public String currentMemoryId(String userId) {
    String sessionId = currentSessionId(userId);
    return sessionId != null ? SessionMemoryStore.buildMemoryId(userId, sessionId) : null;
}
```

- [ ] **Step 5: 运行相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.conversation.ConversationManagerTest" --tests "com.hirain.aiagent.memory.*"
```

Expected: PASS。若 `ConversationManagerTest` 断言旧 `memoryId` 字符串，应调整测试以验证 conversation metadata，而不是验证旧短期 key。

### Task 2.2: 明确 SQLite 迁移边界

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
- Test: Android instrumentation 可选，JVM 单测不直接依赖 Android SQLite。

- [ ] **Step 1: 提升 DB version**

将：

```java
private static final int DB_VERSION = 2;
```

改为：

```java
private static final int DB_VERSION = 3;
```

- [ ] **Step 2: 在 `onUpgrade(...)` 添加 v3 注释和轻量迁移**

因为 demo 阶段可以接受旧短期消息不自动合并，本轮不尝试从旧 `userId_sessionId` 反推并迁移全部历史。v3 迁移只确保表存在，旧记录不会被新 session-scoped key 读取。

```java
if (oldVersion < 3) {
    db.execSQL(
            "CREATE TABLE IF NOT EXISTS session_messages (" +
                    "memory_id TEXT PRIMARY KEY, " +
                    "messages TEXT NOT NULL)");
}
```

在方法注释中写明设计原因：旧短期 memory id 包含 userId，不符合同 session 多发言人共享目标；demo 阶段不做自动合并，避免把不同旧用户历史错误混入同一个 session。

- [ ] **Step 3: 阶段验收**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
.\gradlew.bat assembleDebug
```

Expected: unit tests PASS，`assembleDebug` BUILD SUCCESSFUL。

---

## 5. Phase 3: 新增 `SessionChatMemoryProvider` 和 Context 预留只读接口

**目标：** Memory 模块提供统一入口，将 LangChain4j `MessageWindowChatMemory` 绑定到 AIAgent `SessionMemoryStore`，并屏蔽缓存和 live/store 同步策略；`sessionId` 必须已经由 Phase 0 解析完成。同时提供最小只读快照接口，给后续 Context 模块读取 memory 数据，但不在本轮决定 prompt 组装。

### Task 3.1: 定义 provider 并支持测试注入

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/SessionChatMemoryProviderTest.java`

- [ ] **Step 1: 写 provider 测试**

测试使用 LangChain4j `ChatMemoryStore` fake，避免 JVM 单测依赖 Android SQLite：

```java
package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SessionChatMemoryProviderTest {
    @Test
    public void sameSessionReturnsSameChatMemoryInstance() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50);

        ChatMemory first = provider.getOrCreate("session-1");
        ChatMemory second = provider.getOrCreate("session-1");

        assertSame(first, second);
    }

    @Test
    public void differentSessionsAreIsolated() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50);

        provider.getOrCreate("session-1").add(UserMessage.from("A"));
        provider.getOrCreate("session-2").add(UserMessage.from("B"));

        assertEquals("A", ((UserMessage) provider.getOrCreate("session-1").messages().get(0)).singleText());
        assertEquals("B", ((UserMessage) provider.getOrCreate("session-2").messages().get(0)).singleText());
    }

    @Test
    public void evictsLeastRecentlyUsedSessionWhenCacheLimitExceeded() {
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(new FakeStore(), 50, 2);

        ChatMemory first = provider.getOrCreate("session-1");
        provider.getOrCreate("session-2");
        provider.getOrCreate("session-1");
        provider.getOrCreate("session-3");

        assertSame(first, provider.getOrCreate("session-1"));
    }

    @Test
    public void replaceMessagesUpdatesCachedChatMemoryImmediately() {
        FakeStore store = new FakeStore();
        SessionChatMemoryProvider provider = new SessionChatMemoryProvider(store, 50);
        ChatMemory memory = provider.getOrCreate("session-1");
        memory.add(UserMessage.from("old"));

        provider.replaceMessages("session-1", List.of(UserMessage.from("summary")));

        assertSame(memory, provider.getOrCreate("session-1"));
        assertEquals(1, provider.getOrCreate("session-1").messages().size());
        assertEquals("summary",
                ((UserMessage) provider.getOrCreate("session-1").messages().get(0)).singleText());
        assertEquals("summary",
                ((UserMessage) store.getMessages("session-1").get(0)).singleText());
    }

    private static final class FakeStore implements ChatMemoryStore {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            String json = data.get(String.valueOf(memoryId));
            return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(String.valueOf(memoryId), ChatMessageSerializer.messagesToJson(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(String.valueOf(memoryId));
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SessionChatMemoryProviderTest"
```

Expected: FAIL，原因是 provider 尚不存在。

- [ ] **Step 3: 实现 provider**

```java
package com.hirain.aiagent.memory;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * Session 级 ChatMemory 提供者。
 * 设计原因：短期记忆归属于座舱会话 sessionId，而不是 userId 或 personaId；
 * LangChain4j 继续负责 ChatMemory 窗口语义，AIAgent 负责选择和持久化 session key。
 */
public class SessionChatMemoryProvider {
    private static final int DEFAULT_MAX_CACHED_SESSIONS = 50;

    private final ChatMemoryStore store;
    private final int defaultMaxMessages;
    private final Map<String, ChatMemory> cache;

    public SessionChatMemoryProvider(ChatMemoryStore store, int defaultMaxMessages) {
        this(store, defaultMaxMessages, DEFAULT_MAX_CACHED_SESSIONS);
    }

    public SessionChatMemoryProvider(ChatMemoryStore store, int defaultMaxMessages, int maxCachedSessions) {
        this.store = store;
        this.defaultMaxMessages = Math.max(1, defaultMaxMessages);
        int safeMaxCachedSessions = Math.max(1, maxCachedSessions);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatMemory> eldest) {
                return size() > safeMaxCachedSessions;
            }
        };
    }

    public synchronized ChatMemory getOrCreate(String sessionId) {
        return getOrCreate(sessionId, defaultMaxMessages);
    }

    public synchronized ChatMemory getOrCreate(String sessionId, int maxMessages) {
        String memoryId = SessionMemoryIds.shortTermMemoryId(sessionId);
        ChatMemory existing = cache.get(memoryId);
        if (existing != null) {
            return existing;
        }
        ChatMemory created = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(Math.max(1, maxMessages))
                .chatMemoryStore(store)
                .build();
        cache.put(memoryId, created);
        return created;
    }

    public synchronized void replaceMessages(String sessionId, List<ChatMessage> messages) {
        ChatMemory memory = getOrCreate(sessionId);
        memory.clear();
        for (ChatMessage message : messages) {
            memory.add(message);
        }
    }

    public synchronized void clear(String sessionId) {
        String memoryId = SessionMemoryIds.shortTermMemoryId(sessionId);
        cache.remove(memoryId);
        store.deleteMessages(memoryId);
    }
}
```

- [ ] **Step 4: 运行 provider 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.SessionChatMemoryProviderTest"
```

Expected: PASS。

### Task 3.2: 定义 Context 预留只读接口

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/memory/MemorySnapshot.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/MemorySnapshotTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.hirain.aiagent.memory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;

public class MemorySnapshotTest {
    @Test
    public void snapshotDefensivelyCopiesMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("hello"));

        MemorySnapshot snapshot = new MemorySnapshot("session-1", messages, 10, "summary");
        messages.clear();

        assertEquals(1, snapshot.messages().size());
        assertEquals("session-1", snapshot.sessionId());
        assertEquals(10, snapshot.tokenEstimate());
        assertEquals("summary", snapshot.summary());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.MemorySnapshotTest"
```

Expected: FAIL，原因是 `MemorySnapshot` 尚不存在。

- [ ] **Step 3: 实现只读快照值类型**

```java
package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Context 预留用的只读短期记忆快照。
 * 设计原因：Memory 模块只负责提供当前 session 的记忆数据，不决定这些数据是否进入本轮 prompt。
 */
public final class MemorySnapshot {
    private final String sessionId;
    private final List<ChatMessage> messages;
    private final int tokenEstimate;
    private final String summary;

    public MemorySnapshot(String sessionId, List<ChatMessage> messages,
                          int tokenEstimate, String summary) {
        this.sessionId = SessionMemoryIds.shortTermMemoryId(sessionId);
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<>(messages))
                : List.of();
        this.tokenEstimate = tokenEstimate;
        this.summary = summary != null ? summary : "";
    }

    public String sessionId() { return sessionId; }
    public List<ChatMessage> messages() { return messages; }
    public int messageCount() { return messages.size(); }
    public int tokenEstimate() { return tokenEstimate; }
    /**
     * 返回已有压缩摘要的 best-effort 结果。
     * 设计原因：当前摘要仍存放在普通 UserMessage 中，尚不是稳定结构化字段；
     * Context 阶段不能依赖该字段必定存在。
     */
    public String summary() { return summary; }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.MemorySnapshotTest"
```

Expected: PASS。

### Task 3.3: 接入 `MemoryOrchestrator`

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`

- [ ] **Step 1: 在 `MemoryOrchestrator` 构造 provider**

增加字段：

```java
private final SessionChatMemoryProvider sessionChatMemoryProvider;
```

构造函数中初始化，不在 MemoryOrchestrator 内隐藏固定窗口大小：

```java
this.sessionChatMemoryProvider = new SessionChatMemoryProvider(sessionStore, 50);
```

这里的 `50` 仅作为 provider 默认值；TEXT 主路径在 Phase 4 调用 `chatMemoryForSession(sessionId, config.maxMemoryMessages())`。本轮只支持 TEXT 统一窗口大小，后续如需不同 persona / 场景窗口，应把窗口大小固化为 session memory policy，而不是让同一 session 在不同轮次切换窗口。

- [ ] **Step 2: 暴露短期 ChatMemory 和 Context 预留读取入口**

```java
public dev.langchain4j.memory.ChatMemory chatMemoryForSession(String sessionId, int maxMessages) {
    return sessionChatMemoryProvider.getOrCreate(sessionId, maxMessages);
}

public void clearSessionMemory(String sessionId) {
    sessionChatMemoryProvider.clear(sessionId);
}

public List<ChatMessage> readSessionMessages(String sessionId) {
    return sessionStore.getMessages(SessionMemoryIds.shortTermMemoryId(sessionId));
}

public MemorySnapshot getMemorySnapshot(String sessionId) {
    List<ChatMessage> messages = readSessionMessages(sessionId);
    String summary = extractExistingSummary(messages);
    return new MemorySnapshot(sessionId, messages, estimateTokensForSnapshot(messages), summary);
}

public String getSummarizedMemory(String sessionId, int maxChars) {
    String text = extractExistingSummary(readSessionMessages(sessionId));
    if (text.length() <= maxChars) return text;
    return text.substring(0, Math.max(0, maxChars));
}

private String extractExistingSummary(List<ChatMessage> messages) {
    for (ChatMessage message : messages) {
        if (message instanceof UserMessage) {
            String text = ((UserMessage) message).singleText();
            if (text != null && text.startsWith("【对话摘要】")) {
                return text;
            }
        }
    }
    return "";
}
```

`extractExistingSummary(...)` 只读取现有压缩结果，不主动触发压缩；返回值是 best-effort，因为当前摘要仍依赖 `【对话摘要】` 文本前缀。`estimateTokensForSnapshot(...)` 可以复用现有粗略估算逻辑，作为 Context 后续预算输入的低精度参考；真正 token budget 策略仍留到 Context 阶段。

- [ ] **Step 3: 修改 `onTurnComplete(...)` 签名**

新增带 `sessionId` 的重载：

```java
public void onTurnComplete(String userId, String sessionId, List<ChatMessage> currentMessages,
                            int tokenEstimate, String userMessage, String aiResponse,
                            AgentTraceRecorder trace) {
    UserMemoryContext ctx = getUserContext(userId);
    ctx.extractAndStore(userMessage, aiResponse, trace);

    List<ChatMessage> compressed = ctx.compressIfNeeded(currentMessages, tokenEstimate, trace);
    if (compressed != currentMessages) {
        sessionChatMemoryProvider.replaceMessages(sessionId, compressed);
    }
}
```

保留旧重载时必须只作为测试/legacy 兼容路径；生产 TEXT 主路径必须传入 Phase 0 解析出的 `sessionId`。压缩写回不得直接调用 `sessionStore.updateMessages(...)`，否则会导致 provider 缓存中的 live `ChatMemory` 与 store 分裂。

- [ ] **Step 4: 阶段验收**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
.\gradlew.bat assembleDebug
```

Expected: PASS / BUILD SUCCESSFUL。

---

## 6. Phase 4: 极窄接入 AgentLoop，使真实 ChatMemory 按 session 生效

**目标：** 不做 Context prompt 接管，但必须先把长期记忆 `SystemMessage` 改成本轮 transient 消息，再让 `AgentLoopOrchestrator` 每轮使用 Memory 模块提供的 session-scoped `ChatMemory`。Phase 4 不允许交付“session ChatMemory 已共享，但当前用户长期记忆仍被持久化进共享短期历史”的中间状态。

### Task 4.0: 先移除 SystemMessage 持久化路径

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/factory/AgentConfigFactoryTest.java` or new focused test

- [ ] **Step 1: 写泄漏风险测试**

新增断言：session-scoped `ChatMemory.messages()` 不持久化 `SystemMessage`，也不持久化包含 `【长期记忆】` 的内容。

核心断言：

```java
assertTrue(chatMemory.messages().stream().noneMatch(SystemMessage.class::isInstance));
assertFalse(chatMemory.messages().toString().contains("【长期记忆】"));
```

该测试必须在 Task 4.1 接入 session-scoped ChatMemory 之前存在，防止多人共享短期历史后把 user_a 的长期记忆暴露给 user_b。

- [ ] **Step 2: 将 system prompt 改成本轮 transient message**

将长期记忆 system prompt 从持久化 `chatMemory.add(SystemMessage.from(sysPrompt))` 改为本轮局部 `SystemMessage`。建议新增 helper：

```java
private SystemMessage buildSystemPromptMessage(String userId, String personaId) {
    String templateName = com.hirain.aiagent.prompt.PromptConstants.textPersonaTemplateName(personaId);
    String basePrompt = promptManager.render(templateName);
    String sysPrompt = memoryOrchestrator != null
            ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
            : basePrompt;
    return SystemMessage.from(sysPrompt);
}
```

在请求组装时：

```java
List<ChatMessage> allMessages = new ArrayList<>();
allMessages.add(buildSystemPromptMessage(userId, personaId));
allMessages.addAll(transientMessages);
allMessages.addAll(chatMemory.messages());
```

- [ ] **Step 3: 删除或废弃 `injectSystemPrompt(...)` 的持久化写入**

`injectSystemPrompt(...)` 不能继续向 `ChatMemory` 写入 `SystemMessage`。优先删除该方法；如果测试或兼容代码仍引用它，则改名为 transient helper，或保留空壳并加中文注释说明“不可写入短期 store”。

- [ ] **Step 4: 同步移除 TEXT 主路径 `MemoryPreProcessor`**

长期记忆的唯一注入点必须收敛到本轮 transient `SystemMessage`。检查 `AgentConfigFactory` 中 `createTextPersona(...)`、`createChatPersona(...)` 或类似方法；如果 preprocessor chain 中存在 `new MemoryPreProcessor(...)`，从 TEXT 主路径移除。

测试断言：

```java
assertFalse(textPersona.preProcessors().stream()
        .anyMatch(processor -> processor instanceof MemoryPreProcessor));
```

`MemoryPreProcessor` 类本身优先保留为 legacy/no-op，避免影响旧测试或非 TEXT 路径引用，并加中文注释：

```java
// 长期记忆的唯一注入点已经收敛到 AgentLoop 的 transient SystemMessage。
// 该 preprocessor 仅保留给旧配置兼容，不能再把长期记忆拼入用户消息。
```

- [ ] **Step 5: 阶段内先跑核心风险测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorSessionMemoryTest" --tests "com.hirain.aiagent.core.factory.*"
```

Expected: PASS，且 `ChatMemory.messages()` 中没有 `SystemMessage` / `【长期记忆】`；TEXT preprocessor chain 不再包含 `MemoryPreProcessor`。

### Task 4.1: 让 AgentLoop 使用每轮 session ChatMemory

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`

- [ ] **Step 1: 写接线行为测试**

测试目标：同一 `session_id` 的不同 `user_id` 共享短期历史，不同 `session_id` 隔离。测试可以通过 fake `SessionChatMemoryProvider` 或可注入 `ChatMemory` factory 实现；若当前构造函数不支持测试注入，先在 Task 4.1 Step 3 增加包可见构造函数。

测试前置条件：Task 4.0 已完成，`SystemMessage` 不会进入持久化 `ChatMemory`。否则该测试即使通过 session 共享，也会造成长期记忆跨用户可见。

核心断言代码如下：

```java
assertTrue(messagesForSession1.toString().contains("[speaker=user_a] 第一轮"));
assertTrue(messagesForSession1.toString().contains("[speaker=user_b] 第二轮"));
assertFalse(messagesForSession2.toString().contains("第一轮"));
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorSessionMemoryTest"
```

Expected: FAIL，原因是 AgentLoop 仍使用构造时固定 `chatMemory`。

- [ ] **Step 3: 将 `chatMemory` 从固定字段改为每轮局部变量**

将现有字段：

```java
private final ChatMemory chatMemory;
```

改为兼容 fallback 字段：

```java
private final ChatMemory fallbackChatMemory;
```

构造函数中：

```java
this.fallbackChatMemory = createChatMemory(context);
```

在 `execute(...)` 开头读取 Phase 0 已解析的 `session_id`。这里不再 resolve，也不允许 TEXT 主路径 fallback：

```java
String sessionId = extraContext != null
        ? (String) extraContext.get("session_id")
        : null;
if (sessionId == null || sessionId.trim().isEmpty()) {
    state.markError();
    return AgentResult.error(ErrorType.INVALID_CONFIG,
            "resolved sessionId is required before AgentLoop execution");
}
ChatMemory chatMemory = memoryOrchestrator.chatMemoryForSession(
        sessionId, config.maxMemoryMessages());
```

`fallbackChatMemory` 只允许保留给 legacy / 非 TEXT / 明确无持久化记忆策略；正常 TEXT 请求不得访问它。测试中应通过 fake fallback 断言 `wasRead=false`。

请求消息组装继续使用 Task 4.0 的 transient system message：

```java
List<ChatMessage> allMessages = new ArrayList<>();
allMessages.add(buildSystemPromptMessage(userId, personaId));
allMessages.addAll(transientMessages);
allMessages.addAll(chatMemory.messages());
```

- [ ] **Step 4: 用户消息写入 speaker 标记**

将：

```java
chatMemory.add(UserMessage.from(userInput));
```

改为：

```java
chatMemory.add(UserMessage.from(
        com.hirain.aiagent.memory.SpeakerMessageFormatter.formatUserMessage(userId, userInput)));
```

`memoryOrchestrator.onTurnComplete(...)` 仍传入原始 `userInput`，保证长期记忆提取归属于当前发言人且不带 speaker 前缀。

- [ ] **Step 5: `onTurnComplete(...)` 传入 sessionId**

将两个调用点改为：

```java
memoryOrchestrator.onTurnComplete(
        userId, sessionId, chatMemory.messages(),
        estimateTokens(chatMemory.messages()),
        userInput, output, trace);
```

安全否决分支也使用同一签名。

- [ ] **Step 6: 清理 `cleanMemory()` 语义**

`cleanMemory()` 不应只清 fallback memory。改为根据当前 active session 清理，或保留 fallback 并新增：

```java
public void cleanMemory(String sessionId) {
    if (memoryOrchestrator != null && sessionId != null) {
        memoryOrchestrator.clearSessionMemory(sessionId);
        return;
    }
    throw new IllegalArgumentException("sessionId is required for session-aware memory cleanup");
}
```

旧无参 `cleanMemory()` 如需保留，只能清理 legacy fallback，并在中文注释中标明不属于 TEXT session-aware 主路径。

- [ ] **Step 7: 运行 AgentLoop 相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.*"
```

Expected: PASS。若 trace 测试依赖旧 `prompt.chat_messages` 数量，应更新断言为 session-scoped 后的真实消息数量。

### Task 4.2: 停止固定 `"ChatMemory"` 主路径

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java` only if required
- Test: existing core tests

- [ ] **Step 1: 修改 TEXT persona 配置注释**

保留：

```java
.chatMemoryStoreId(memoryId)
```

时，必须将 `memoryId` 设为兼容 fallback，而不是主路径：

```java
String memoryId = "FallbackChatMemory";
```

并增加中文注释说明：

```java
// session-scoped 主路径由 MemoryOrchestrator.chatMemoryForSession(sessionId, maxMessages) 决定；
// 此字段仅保留给 legacy / 非 TEXT 兼容路径；正常 TEXT 请求缺失 resolved sessionId 时应失败。
```

- [ ] **Step 2: 运行配置测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentConfigTraceTest"
```

Expected: PASS。

### Task 4.3: 修正 Service 中 memory 清理调用

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 修正 `ClearChatMemory` 分支**

当前逻辑把 `request.sessionId` 当作 `userId` 传入 `startNewSession(...)`，这是身份混淆。改为：

```kotlin
val userId = normalizeUserId(request)
val sessionId = request.sessionId
    ?: memoryOrchestrator.resolveSessionId(
        userId, null, request.text, request.personaId, request.sourceApp)
chatOrchestrator.cleanMemory(sessionId)
```

本计划固定 `ClearChatMemory` 为“清除当前共享 session 的短期消息”，不创建新 session。若未来产品要“开启新会话”，应走 `createConversation` / `startNewSession` 语义，不复用清理命令。

- [ ] **Step 2: 阶段验收**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.*" --tests "com.hirain.aiagent.memory.*" --tests "com.hirain.aiagent.runtime.*"
.\gradlew.bat assembleDebug
```

Expected: PASS / BUILD SUCCESSFUL。

---

## 7. Phase 5: 长期记忆与共享短期历史的边界加固

**目标：** 长期记忆按当前发言人 `userId` 读写，同时避免长期记忆污染共享 session 短期历史。

### Task 5.1: 明确长期记忆写入使用原始用户文本

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Existing: `app/src/main/java/com/hirain/aiagent/memory/UserMemoryContext.java`
- Test: `app/src/test/java/com/hirain/aiagent/memory/MemoryExtractorTraceTest.java` or new focused test

- [ ] **Step 1: 增加测试断言**

在 AgentLoop session memory 测试中增加断言：短期 ChatMemory 中包含 `[speaker=user_a]`，但传给 `MemoryOrchestrator.onTurnComplete(...)` 的 `userMessage` 是原始文本 `"打开空调"`。

如果使用 fake `MemoryOrchestrator` 不方便，可将 `SpeakerMessageFormatter` 和 `onTurnComplete` 调用路径拆成包可见 helper，并测试 helper 输出。

- [ ] **Step 2: 确认实现**

`AgentLoopOrchestrator` 中应同时满足：

```java
chatMemory.add(UserMessage.from(SpeakerMessageFormatter.formatUserMessage(userId, userInput)));
memoryOrchestrator.onTurnComplete(userId, sessionId, chatMemory.messages(),
        estimateTokens(chatMemory.messages()), userInput, output, trace);
```

- [ ] **Step 3: 运行测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorSessionMemoryTest"
```

Expected: PASS。

### Task 5.2: 复核长期记忆唯一注入点

**Files:**
- Verify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Verify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Verify: `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`

- [ ] **Step 1: 复核注入点**

确认 Task 4.0 已经形成以下不变量：

- `AgentLoopOrchestrator` 只通过 transient `SystemMessage` 注入当前发言人长期记忆。
- `ChatMemory.messages()` 中没有 `SystemMessage` 和 `【长期记忆】`。
- TEXT 主路径 preprocessor chain 不包含 `MemoryPreProcessor`。
- `MemoryPreProcessor` 如仍保留，必须是 legacy/no-op，不再读取长期记忆并生成 `UserMessage("【用户记忆参考】...")`。

核心断言仍保留：

```java
assertFalse(textPersona.preProcessors().stream()
        .anyMatch(processor -> processor instanceof MemoryPreProcessor));
```

- [ ] **Step 2: 运行配置与 AgentLoop 测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.factory.*" --tests "com.hirain.aiagent.core.AgentLoopOrchestratorSessionMemoryTest"
```

Expected: PASS。

### Task 5.3: 可选清理压缩输入中的 speaker 噪声

**Files:**
- Optional Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
- Optional Test: `app/src/test/java/com/hirain/aiagent/memory/MemoryCompressorTest.java`

该任务不是阻塞项。只有当现有压缩摘要确实会出现大量 `用户：[speaker=user_a] ...` 这类重复前缀时执行。

- [ ] **Step 1: 增加摘要格式测试**

测试目标：压缩 prompt 或摘要输入中，speaker 信息保留为可读身份，不重复出现机器前缀。

可接受输出示例：

```text
用户(user_a)：打开空调
助手：已为你打开空调
```

不建议输出：

```text
用户：[speaker=user_a] 打开空调
```

- [ ] **Step 2: 实现最小格式化**

复用 `SpeakerMessageFormatter` 的解析能力，或在 `MemoryCompressor` 内增加私有 helper，把 `[speaker=...]` 转成可读标签。不要影响 `ChatMemory` 中真实存储的 speaker 前缀，因为该前缀仍是多人共享短期历史的必要元数据。

- [ ] **Step 3: 运行 memory 压缩测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.MemoryCompressorTest"
```

Expected: PASS。

---

## 8. Phase 6: 文档、回归和验收

**目标：** 更新文档并通过阶段回归验证本轮改造确实改变真实短期记忆主路径。

### Task 6.1: 更新 memory overview

**Files:**
- Modify: `docs/overview/memory-module-overview.md`
- Optional: Create `docs/testresult/2026-07-09-memory-session-chatmemory-testresult.md`

- [ ] **Step 1: 更新概述中的架构描述**

文档应明确写入：

```text
短期记忆：session-scoped，memoryId=sessionId，由 LangChain4j MessageWindowChatMemory + AIAgent SessionMemoryStore 共同实现。
长期记忆：user-scoped，读写 key=userId。
persona：不参与 memory key，只影响 prompt/template/行为策略。
sessionId：全局共享会话 id；user -> active session 只是当前发言人的指针。
缺失 sessionId：Runtime 在 Context 构建前解析为 resolvedSessionId，并写入 RequestSession/ContextFrame/Response/Trace。
Context：下一阶段负责决定本轮 prompt 中放入哪些 memory/context 内容；本轮只完成 memory 存储与 ChatMemory 主路径接线。
```

- [ ] **Step 2: 删除或修正文档中的过期说法**

重点修正：

- `SessionMemoryStore` 使用 `(userId_sessionId)` 作为 memoryId 的说法。
- `SessionChatMemoryProvider 不存在` 的说法。
- `PersistentChatMemorySqlite("ChatMemory")` 是 TEXT 主路径的说法。
- 长期记忆注入边界：Task 4.0 完成后，长期记忆只作为本轮 transient `SystemMessage`，不写入短期 store；`MemoryPreProcessor` 不再形成第二条长期记忆注入路径。
- 会话生命周期边界：`deleteConversation(userId, sessionId)` 在本轮定义为删除全局共享 session，不是只删除某个 user 的列表入口。

### Task 6.2: 总体验证

**Files:**
- Test command only

- [ ] **Step 1: 运行 memory/core/runtime/context 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*" --tests "com.hirain.aiagent.core.*" --tests "com.hirain.aiagent.runtime.*" --tests "com.hirain.aiagent.context.*"
```

Expected: PASS。

- [ ] **Step 2: 运行全量 JVM 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: PASS。

- [ ] **Step 3: 构建 debug 包**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 如有设备或模拟器，运行 instrumentation 冒烟测试**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expected: BUILD SUCCESSFUL 且测试通过。若当前环境没有连接设备，应在测试结果文档中记录“未运行 connectedDebugAndroidTest，原因：无设备/模拟器”。

- [ ] **Step 5: 真实 SQLite 行为验证**

如果已有 instrumentation 测试框架，新增或运行针对 `SessionMemoryStore` 的设备/模拟器测试；如果当前环境没有设备，则在测试结果文档中记录手工验证待办。验证点必须包含：

```text
1. v2 -> v3 upgrade 不崩溃，session_messages 表按新 schema 可读写。
2. LangChain4j MessageWindowChatMemory(id=sessionId, store=SessionMemoryStore) 能正常 update/get。
3. deleteGlobalSession(sessionId) 删除所有 user 的 sessions metadata，并删除 session_messages 中 memory_id=sessionId 的消息。
4. provider.replaceMessages(sessionId, compressed) 后，不重建 provider，getOrCreate(sessionId).messages() 立即返回压缩后的消息。
```

### Task 6.3: 人工验收用例

**Files:**
- Optional: `docs/testresult/2026-07-09-memory-session-chatmemory-testresult.md`

- [ ] **Step 1: 短期验收准备**

短期隔离/共享验收时，应临时禁用 `MemoryExtractor`，或使用明确不会被长期记忆提取的短句。不要用“我喜欢 22 度”这类偏好句测试短期隔离，因为它可能被写入长期记忆并干扰结论。

- [ ] **Step 2: 同 session 多发言人共享短期上下文**

测试序列：

```text
sessionId=S_demo_1, userId=user_a: "上一句话的临时编号是 7788"
sessionId=S_demo_1, userId=user_b: "刚才他说的临时编号是多少？"
```

Expected:

- 模型可从短期历史中看到 user_a 的上一轮发言。
- 短期历史里 user_a 的消息带 `[speaker=user_a]`。
- 不依赖长期记忆也能回答 `7788`。

- [ ] **Step 3: 不同 session 隔离短期上下文**

测试序列：

```text
sessionId=S_demo_1, userId=user_a: "上一句话的临时编号是 alpha-7788"
sessionId=S_demo_2, userId=user_a: "上一句话的临时编号是什么？"
```

Expected:

- `S_demo_2` 不应从短期历史中读取 `S_demo_1` 的 `alpha-7788`。
- 如果长期记忆未禁用且模型仍答出该值，必须检查 `LongTermMemoryStore`，不能直接判定短期隔离失败。

- [ ] **Step 4: persona 不切分短期记忆**

测试序列：

```text
sessionId=S_demo_1, userId=user_a, personaId=chat: "临时编号是 7788"
sessionId=S_demo_1, userId=user_a, personaId=friendly: "临时编号是多少？"
```

Expected:

- 第二轮仍能看到第一轮短期历史。
- `personaId` 变化只影响系统提示词风格，不改变 short-term memory key。

- [ ] **Step 5: 长期记忆按当前发言人读取**

长期记忆验收应与短期隔离分开执行。执行后检查 `LongTermMemoryStore` 中 user_a/user_b 的记录，确认提取已经完成，再发起查询轮。

测试序列：

```text
sessionId=S_demo_1, userId=user_a: "我喜欢 22 度空调"
sessionId=S_demo_1, userId=user_b: "我喜欢 26 度空调"
sessionId=S_demo_2, userId=user_a: "我喜欢多少度空调？"
```

Expected:

- user_a 的长期记忆是 22 度。
- user_b 的长期记忆是 26 度。
- `S_demo_2` 中 user_a 不读取 user_b 的长期记忆。

---

## 9. 风险和回退策略

### 风险 1: LangChain4j `MessageWindowChatMemory.builder().id(...)` API 与当前版本不一致

处理方式：

- 先查看当前依赖版本下 `MessageWindowChatMemory` builder 的可用方法。
- 如果没有 `.id(...)`，则保留 LangChain4j `ChatMemoryStore`，但通过 provider 创建一个适配器，把 `sessionId` 作为 store 调用时的 memory id。
- 不为了该问题自研完整 `ChatMemory`。

### 风险 2: 旧数据库中已有 `userId_sessionId` 格式短期消息

处理方式：

- demo 阶段默认不自动合并旧短期消息，避免把不同旧用户历史错误混入新 session-scoped 短期历史。
- 若必须保留旧数据，另开迁移任务：扫描 `sessions(user_id, session_id)`，将唯一匹配的旧 `userId_sessionId` 消息迁移到 `sessionId`，出现多个 user 同 session 时跳过并记录日志。

### 风险 3: AgentLoop 改动范围扩大

处理方式：

- 只允许三类改动：按 session 获取 ChatMemory、speaker 标记用户消息、避免长期记忆持久化到共享短期 store。
- 不在本轮引入 Context provider 的完整短期上下文渲染。
- 不重写 tool loop、safety、terminator、collector。

### 风险 4: 缺失 sessionId 的策略影响现有调用方

处理方式：

- 本计划固定采用 active session / 自动创建策略，不再保留“返回参数错误”的并行分支。
- Runtime 必须在 Context 构建前解析 `resolvedSessionId`，并在 response 与 trace 中返回给调用方。
- 不允许静默写入 `"default_session"` 作为真实业务会话。

### 风险 5: SystemMessage 去持久化与 session 共享顺序颠倒

处理方式：

- Phase 4 必须先完成 Task 4.0，再执行 Task 4.1；不能先让多人共享同一个 session ChatMemory，再留待 Phase 5 处理长期记忆泄漏。
- 阶段验收必须包含 `ChatMemory.messages()` 不含 `SystemMessage`、不含 `【长期记忆】` 的断言。
- 如果 Task 4.0 测试无法稳定通过，应暂停 session-scoped ChatMemory 主路径切换，而不是继续后续接线。

### 风险 6: provider 缓存与 SQLite store 分裂

处理方式：

- 压缩写回统一调用 `SessionChatMemoryProvider.replaceMessages(sessionId, compressedMessages)`。
- 不允许 `MemoryOrchestrator.onTurnComplete(...)` 在生产 TEXT 主路径直接调用 `sessionStore.updateMessages(...)`。
- provider 测试必须覆盖不重建 provider 的情况下，下一轮 `getOrCreate(sessionId).messages()` 立即看到压缩后的消息。

### 风险 7: user-scoped metadata 与全局 session messages 生命周期不一致

处理方式：

- 本轮固定 `deleteConversation(userId, sessionId)` 为全局共享 session 删除。
- 删除时按 `session_id` 删除所有 user metadata 行，并按 `memory_id=sessionId` 删除短期消息。
- 不实现“只隐藏某 user 的会话入口”；如未来需要，应新增单独 pointer-only API。

---

## 10. Self-Review

- Spec coverage: 覆盖了 Memory 存储边界、LangChain4j ChatMemory + SessionMemoryStore 底层机制、sessionId 短期共享、userId 长期记忆、persona 非 key、Context 后续接管、本轮 AgentLoop 极窄接线。
- Review coverage: 已处理第一轮评审中的两个阻塞问题：SystemMessage 去持久化前置到 Phase 4；Context 预留接口明确为 `readSessionMessages(...)`、`getMemorySnapshot(...)`、`getSummarizedMemory(...)`。
- Feasibility review coverage: 已处理第二轮可行性评审中的四个 P0：`resolvedSessionId` 贯穿 Runtime/Context/Response/Trace；压缩写回同步 live ChatMemory 与 store；全局 sessionId 与 user-scoped metadata 的删除语义固定；TEXT 主路径禁止 fallback ChatMemory 正常生效。
- Non-blocking coverage: 已纳入 `MemoryPreProcessor` 提前治理、provider LRU 缓存上限、`maxMessages` 配置边界、`MemorySnapshot.summary()` best-effort 标注、真实 SQLite 验证，以及压缩摘要 speaker 噪声的可选优化。
- Placeholder scan: 计划中没有未定义占位；实施前确认项明确写出默认策略和替代策略。
- Type consistency: 新增类型为 `SessionIdResolver`、`SessionChatMemoryProvider`、`MemorySnapshot`、`SpeakerMessageFormatter`；短期 key 方法统一为 `SessionMemoryIds.shortTermMemoryId(...)`；AgentLoop 写回统一调用 `MemoryOrchestrator.onTurnComplete(userId, sessionId, ...)`；压缩替换统一调用 `SessionChatMemoryProvider.replaceMessages(...)`。
- Scope check: 本计划是一个可独立验证的 memory 主路径改造；Context prompt 接管被明确排除到下一阶段。
