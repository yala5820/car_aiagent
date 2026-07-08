# Agent Request Conversation Management Plan — 二次审查

**审查日期：** 2026-07-07
**计划文件：** [`docs/plan/agent-request-conversation-management-plan.md`](../plan/agent-request-conversation-management-plan.md)
**审查人：** Claude Code

---

## 一、总体评估

**计划质量良好，通过审查。** 上一轮发现的 6 个问题已全部修复：SessionManager 改为按用户隔离 active session、personaId 传递链路简化、`toConversationInfo()` 方法体补全、`newTestSession` 辅助方法定义、RuntimeResult 构造函数直接扩展而非二次包装、Trace key 语义辨析。新增 `ConversationSessionGateway` 接口和 `MemoryConversationSessionGateway` 生产实现，符合可测试性要求。

**发现 1 个中危遗留问题和 1 个低危细节，其余所有改动点均已验证无误。**

---

## 二、中危问题

### 2.1 `RequestSessionFactory.create()` 签名变更后，旧测试调用点未在计划中列出

**位置：** Phase 3.1 Task 3.1 Step 2

计划将 `create()` 签名从：

```java
create(AgentRequest, String personaId, TraceContext, IntentResult, ToolGroupSelectionResult)
```

改为：

```java
create(AgentRequest, TraceContext, IntentResult, ToolGroupSelectionResult)
```

但 `RequestSessionFactoryTest` 中 3 个现有测试直接调用了旧签名：

```java
// 现有测试调用（将从第 2 个参数开始报编译错误）：
factory.create(request, "chat", traceContext, intentResult, ...);
factory.create(request, "chat", traceContext, intentResult, ...);
```

同时 `AgentRuntime` 的 `startSession()` 也需同步更新为无 `personaId` 参数的调用。

**影响：** 实施 Phase 3.1 时，这些旧调用点会产生编译错误，需一并修改。如果不提前标注，实施者可能花时间排查"为什么测试跑不了"。

**建议：** 在 Phase 3.1 Step 2 的实施说明中补充一句——"更新 `RequestSessionFactoryTest` 的所有现有调用点，去掉第 2 个 `personaId` 参数；同步更新 `AgentRuntime.startSession()` 中的 factory.create 调用。"

---

## 三、低危问题

### 3.1 `RuntimeResult` 所有工厂方法的参数扩展在计划中只展示了一个样本

**位置：** Phase 5.1 Task 5.1 Step 1-2

计划正确强调"直接把三项加入构造函数和所有工厂方法"，但只展示了 `fromAgentResult()` 的扩展样本，未列出 `success()` / `failure()` / `timeout()` / `fromException()` 的完整新签名。

**影响：** 实施者需要自行推导所有工厂方法的参数变更。`RuntimeResult` 是核心类型，漏改某个工厂方法的参数会导致编译错误或因 `null` 字段产生运行时问题。

**建议：** 增加一句明确要求——"`RuntimeResult` 的 6 个工厂方法（`success` / `failure` / `timeout` / `cancelled` / `fromAgentResult` / `fromException`）必须**全部**在构造函数中增加 `userId` / `personaId` / `clientMessageId` 三个参数，没有例外。"

---

## 四、已核实的设计修正（上轮审查问题）

| 上轮问题 | 修正状态 |
|----------|---------|
| SessionManager 单例 + 多用户 active session 冲突 | ✅ 改用 `ConcurrentHashMap<String, ActiveSessionState>` 按用户隔离 |
| `personaId` 在 AgentRuntime → Factory 之间冗余传递 | ✅ 移除 `create()` 的独立 `personaId` 参数，只从 request 读取 |
| `ConversationManager.toConversationInfo()` 方法体缺失 | ✅ 已定义完整字段映射、`active`/`endedAt=0`/`messageCount` 等规则 |
| `ActiveRequestRegistryTest.newTestSession` 未定义 | ✅ 已给出完整辅助方法 body |
| `RuntimeResult.withRequestMeta()` 两种方案未定 | ✅ 选定直接扩展构造函数和工厂方法 |
| Trace key `agent.user.id` 与已有 `user.id` 语义重叠 | ✅ 已明确 TraceManager 写入的 root key 不变，Runtime 只补充 `client_message.id` |
| AIAgentService 在创建 Trace 前补齐缺失 `requestId` | ✅ Phase 3.3 `ensureRequestId()` 方法 |
| `ALREADY_FINISHED` 需要维护短期 finished cache | ✅ Phase 4.1 `finishedRequests` ConcurrentHashMap + 60s TTL |

---

## 五、已核实无误的设计点

| 检查项 | 状态 |
|--------|------|
| AgentRequest Parcelable 字段末尾追加，不破坏 IPC 序列化顺序 | ✅ |
| AgentResponse Parcelable 字段末尾追加 | ✅ |
| ConversationListResponse 用 ArrayList 包裹避免 AIDL 泛型问题 | ✅ |
| CancelRequestResult 三种状态全 + finished cache | ✅ |
| ConversationRequest 包含 userId/sessionId/personaId/title/sourceApp/timestamp | ✅ |
| ConversationInfo 含 active/endedAt/messageCount/tokenEstimate/compressionCount | ✅ |
| Phase 3.2 AgentRuntime.startSession() 公共签名不变 | ✅ 仍为 `(AgentRequest, TraceContext)` |
| Phase 3 personaId 白名单 + fallback 到 chat | ✅ |
| Phase 4 取消语义：协作式取消，不硬中断 HTTP | ✅ |
| Phase 4 取消添加 timeout runnable 移除 + late result 抑制 | ✅ |
| Phase 5 RuntimeResult 元信息闭环：Session → Result → Mapper → Response | ✅ |
| VOICE/IMAGE/CONTROL 不迁移 | ✅ |

---

## 六、审查结论

**计划通过审查，可以进入实施阶段。** 中危问题 2.1 建议在 Phase 3.1 Step 2 中补充一句旧测试调用点更新说明，避免实施初期走弯路。
