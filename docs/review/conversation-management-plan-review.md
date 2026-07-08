# Agent Request Conversation Management 计划审查报告

**审查日期：** 2026-07-07
**计划文件：** [`docs/plan/agent-request-conversation-management-plan.md`](../plan/agent-request-conversation-management-plan.md)
**审查人：** Claude Code

---

## 一、总体评估

计划设计良好，6 个 Phase 的边界和依赖清晰，自检覆盖了 4 个核心需求（会话管理、取消、用户切换、Persona）。对 AIDL Parcelable 末尾追加、ArrayList 泛型兼容、取消语义等细节做了正确决策。

**发现 2 个中危问题和 4 个低危问题，无致命阻塞。**

---

## 二、中危问题

### 2.1 SessionManager 单例与 MemoryOrchestrator 多用户 active session 状态冲突

**位置：** Section 12 风险提示

计划已正确自述此风险：`SessionManager` 用单个 `currentUserId` + `currentSessionId` 维护当前活跃会话，而 `MemoryOrchestrator` 已用 `Map<String, UserMemoryContext>` 支持多用户。当用户 B 调用 `switchSession("user-B", ...)` 后，`SessionManager` 的单例 `currentUserId` 被改写为用户 B，此后用户 A 的后续对话可能访问错误的 `UserMemoryContext`。

计划建议"把 active session 状态下沉到 `UserMemoryContext`"，但 Phase 2 的 Task 2.2 给出的 `switchSession` 实现直接写 `currentUserId.set(userId)`，没有解决多用户覆盖问题：

```java
public boolean switchSession(String userId, String sessionId) {
    currentUserId.set(userId);  // ← 覆盖了上一个用户的 currentUserId
    currentSessionId = sessionId;
    ...
}
```

**影响：** 如果两个用户在同一个 AIDL 连接中交替使用，用户 A 的 ChatMemory 可能写入用户 B 的 SQLite 记录。

**建议：** Phase 2 实施时：
1. 先写一个 `switchSession_crossUserDoesNotCorruptOtherUser` 的失败测试
2. 要么将 `currentSessionId` 迁移到 `UserMemoryContext`，要么在 `AgentRuntime.startSession()` 中直接从 `request.userId` 和 `request.sessionId` 构造 memory 上下文，完全绕过 `SessionManager` 的全局单例

---

### 2.2 AgentRuntime.startSession() 中的 `personaId` 参数冗余传递

**位置：** Phase 3.2, Step 2

计划修改 AgentRuntime.startSession 为：

```java
String personaId = request != null ? request.getPersonaId() : null;
return sessionFactory.create(request, personaId, traceContext,
        intentResult, toolGroupSelectionResult);
```

然后在 RequestSessionFactory.create 中：

```java
String requestPersonaId = nonEmpty(request.getPersonaId(), personaId);
String normalizedPersonaId = nonEmpty(requestPersonaId, "chat");
```

问题：`personaId` 参数来源于 `request.getPersonaId()`，而 factory 内部又再次读取 `request.getPersonaId()`，形成冗余。最终运作正确（三次 fallback 最终到 `"chat"`），但逻辑双向依赖 AgentRequest 和方法参数，后续维护者困惑。

**建议：** 简化为在 factory 内部只读 request：

```java
String normalizedPersonaId = nonEmpty(
    request != null ? request.getPersonaId() : null, "chat");
```

AgentRuntime 不再将 personaId 作为独立参数传入 factory。

---

## 三、低危问题

### 3.1 Plan 中 `ConversationManager.toConversationInfo()` 方法体缺失

**位置：** Phase 2.4, Step 2

计划中 `createConversation()` 调用了 `toConversationInfo(sessionInfo, personaId, title, true)`，但未定义此方法。调用方需要知道：
- `ConversationInfo.active` 如何配置（取 SessionInfo.endedAt == null 还是独立参数）
- `ConversationInfo.messageCount` / `tokenEstimate` 从 SessionInfo 的哪些字段映射

**影响：** 实施时需自行实现此方法，不阻塞。

---

### 3.2 `ActiveRequestRegistry` 中的 `newTestSession` 未定义

**位置：** Phase 4.1, Step 3 测试代码

```java
RequestSession session = newTestSession("req-1");
```

但 `RequestSession` 构造函数是 package-private，测试在同一包下可以访问。`newTestSession` 方法名暗示这是一个测试辅助方法。计划未给出其实现。

**影响：** 测试代码需自行补充测试辅助方法签名，不阻塞。

---

### 3.3 `RuntimeResult.withRequestMeta()` 设计被推荐为次要方案

**位置：** Phase 5.1, Step 1

计划同时提出了两种方法：`withRequestMeta()` 链式调用 vs 直接在构造函数加参数。计划说"推荐直接加"但给出了 `withRequestMeta()` 的方案。这会导致实施者自行选择。

**影响：** 建议在实施前确认选择直接扩展构造函数。

---

### 3.4 `TraceAttributeKeys` 新增字段未枚举是否已存在

**位置：** Phase 5.2

计划中写入的 Trace attribute key `agent.request.id` / `agent.session.id` / `agent.user.id` / `agent.persona` / `agent.client_message.id` 与现有 `TraceManager.startAgentRequest()` 中的 `request.id` / `session.id` / `user.id` 有重叠。需确认：
- 新增字段是重复已有的同名 key 还是替代它们
- 如果是重复，选一套保留；如果语义不同，用不同 key 名

**当前 status：** `startAgentRequest` 已写入 `user.id`（line 130），计划中新的 `writeRequestMetaToTrace` 也写入 `agent.user.id`（不同的 key），不冲突。但计划缺少对已有字段与新增字段的语义辨析。

---

## 四、已核实无误的设计点

| 检查项 | 状态 |
|--------|------|
| `AgentRequest.userId/personaId` 在末尾追加，不破坏 Parcelable 兼容 | ✅ |
| `ConversationListResponse` 使用 `ArrayList<ConversationInfo>` 避免 AIDL 泛型问题 | ✅ |
| `CancelRequestResult` 三种状态（ACCEPTED/NOT_FOUND/ALREADY_FINISHED） | ✅ |
| `TraceManager.startAgentRequest()` 参数签名与计划参数匹配 | ✅ |
| `SessionMemoryStore` 现有能力（createSession 等）与计划一致 | ✅ |
| `RuntimeResult` 当前无 `cancelled` 工厂方法，需新增 | ✅ |
| `RuntimeResponseMapper` 当前不处理 `CANCELLED`，需新增 | ✅ |
| `AIAgentService.kt` 当前 TEXT/VOICE 硬编码 `"chat"` 和乱用 `sessionId` 作为 userId → 计划正确识别此 Bug | ✅ |
| Parcelable 与 AIDL 声明成对出现 | ✅ |
| 6 个阶段均给出明确的文件路径、代码差值和测试命令 | ✅ |
| 取消语义为协作式取消（不保证硬中断 HTTP），已明确声明 | ✅ |

---

## 五、审查结论

**计划可以进入实施阶段。** 重点是中危问题 2.1 的 SessionManager 多用户 active session 冲突，这是唯一可能在运行时产生数据串扰的逻辑缺陷。建议 Phase 2 实施前先在 `UserMemoryContext` 中管理 active session，而非依赖 SessionManager 全局单例。
