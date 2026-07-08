# Agent Request Conversation Management Plan Feasibility Review

审查对象：`docs/plan/agent-request-conversation-management-plan.md`

审查结论：**当前计划不建议直接进入实施**。文档已经修正了部分 Runtime 边界、`userId/sessionId` 混用、Trace requestId 一致性等问题，但仍存在一个会导致目标落空的核心漏洞：计划管理的是 `SessionMemoryStore` 会话元数据，而当前 TEXT 主链路真正给 LLM 使用的短期 `ChatMemory` 仍是 `AgentLoopOrchestrator` 内部固定实例，并未按 `userId/sessionId` 切换。

---

## P0 阻断问题

### 1. 会话管理没有接入 LLM 实际短期记忆，外部“切换对话”不会真正切换上下文

**证据：**

- 计划目标包含“对话管理、用户切换、会话创建/删除/切换”，并声明会话管理复用 `SessionMemoryStore`：`docs/plan/agent-request-conversation-management-plan.md:5-7`、`98-103`。
- 计划边界又明确“不重写 `AgentLoopOrchestrator` 主循环”：`docs/plan/agent-request-conversation-management-plan.md:118-123`。
- 当前真实 TEXT 主链路的短期历史写入位置是 `AgentLoopOrchestrator.chatMemory`：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:67`、`85`、`123-125`、`188`、`220`。
- 该 `chatMemory` 在 orchestrator 构造时创建一次，持久化 store id 来自 `config.chatMemoryStoreId()`：`app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java:366-372`。
- `chat` persona 固定使用 `.chatMemoryStoreId("ChatMemory")`：`app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java:66-72`。

**问题说明：**

计划中的 `createConversation/switchConversation/deleteConversation` 只会改变 `sessions.is_active`、`session_messages` 等会话管理数据。可当前 `chatOrchestrator.execute()` 实际读写的是固定 `PersistentChatMemorySqlite(context, "ChatMemory")`，不是 `SessionMemoryStore.buildMemoryId(userId, sessionId)`，也不是 `ConversationManager` 当前激活会话。

因此按当前计划实现后，很可能出现：

- AIDL 返回“已切换到 session-B”；
- `RequestSession` 中也有正确的 `userId/sessionId`；
- 但 LLM 仍然看到同一个 `ChatMemory` 里的历史消息；
- 删除某个 conversation 的 `session_messages` 也不会清除 `ChatMemory` 中对应历史。

这会直接破坏“对话切换、新建、删除”的用户可见语义。

**必须修订：**

在计划中新增一个明确阶段，解决 TEXT 主链路短期记忆与 `userId/sessionId` 的绑定关系。可选方案至少包括：

1. 将 `AgentLoopOrchestrator` 的 `ChatMemory` 从构造期固定字段改为按请求选择，使用 `RequestSession.userId/sessionId` 生成 memory id。
2. 引入 `ChatMemoryProvider` / `ChatMemoryFactory`，由 Runtime 或 Orchestrator 根据 `userId + sessionId + personaId` 获取对应 `ChatMemory`。
3. 建立 `SessionMemoryStore` 与 LangChain4j `ChatMemoryStore` 的桥接，让 `session_messages` 成为真实短期上下文来源。
4. 如果本阶段不愿触碰主循环，则必须把目标降级为“只做会话元数据管理，不保证 LLM 多对话上下文隔离”，但这与当前需求不一致。

验收必须增加真实链路测试：同一用户创建 A/B 两个会话，在 A 中说一个唯一事实，切到 B 后询问该事实，不能从 A 的短期历史中答出；切回 A 后应能延续 A 的上下文。

---

## P1 高风险问题

### 2. Phase 2 的 fake gateway 单测会掩盖真实集成问题

**证据：**

- 计划使用 fake gateway 避免 JVM 单测依赖 Android SQLite：`docs/plan/agent-request-conversation-management-plan.md:1162-1214`。
- fake `switchSession()` 只判断 session 存在，未真正更新 active 状态：`docs/plan/agent-request-conversation-management-plan.md:1204-1207`。

**问题说明：**

这些测试最多证明 `ConversationManager` 能包装返回值，不能证明：

- 同一用户下多会话切换后只有一个 active；
- `SessionManager.activeSessions` 与 SQLite `is_active` 一致；
- TEXT 主链路真的使用了当前 active session；
- 删除会话会影响真实 LLM 短期上下文。

**建议修订：**

保留 fake 单测，但必须增加至少一类集成级验证：

- `SessionMemoryStore` 的 instrumentation/Robolectric/可替代 SQLite 测试，验证 `createActiveSession/activateSession/deleteSession`。
- `AgentRuntime + AgentLoopOrchestrator` 的会话隔离测试，验证传入不同 `sessionId` 时短期历史源不同。
- 手动验收加入真实 App AIDL 对话切换测试，而不是只验 AIDL 返回值。

### 3. `AgentRequest` Parcelable 兼容性判断不严谨

**证据：**

- 计划 Phase 1 的目标是“旧调用方不设置新字段时行为不变”：`docs/plan/agent-request-conversation-management-plan.md:273-275`。
- 计划要求在现有字段之后追加读写：`docs/plan/agent-request-conversation-management-plan.md:298-320`。
- 当前 `AgentRequest` 的 Parcel 格式以 `extraContext` map 结尾：`app/src/main/java/com/hirain/aiagent/AgentRequest.java:23-38`、`57-75`。

**问题说明：**

当前 `AgentRequest` 没有版本号，也没有 parcel size 边界。新版本 reader 如果读取旧版本 parcel，在读完 map 后继续 `readString()`，存在越界或读到无效数据风险。由于 `extraContext` 是变长结构，简单“末尾追加字段”不能被视为跨版本兼容方案。

**建议修订：**

- 如果 AIAgent 与外部 SDK/JAR 保证同版本升级，文档应明确“不承诺新旧 Parcelable 跨版本混跑兼容”。
- 如果需要兼容旧调用方二进制版本，优先把新增请求元信息临时放入 `extraContext`，或引入新的 versioned Parcelable/新 AIDL 方法。
- 不建议在当前无版本字段的 Parcelable 上宣称追加字段即可兼容。

### 4. 请求取消缺少统一终态控制，仍可能出现 CANCELLED/TIMEOUT/SUCCESS 多响应竞态

**证据：**

- 当前 Service 超时 runnable 会 `dispatchAndClose`，worker 完成后仍走 `responseDispatcher.dispatch`：`app/src/main/java/com/hirain/aiagent/AIAgentService.kt:479-484`、`490-493`。
- 计划新增 `activeTimeouts`，取消时移除 timeout，并在 worker 执行前后检查 cancel：`docs/plan/agent-request-conversation-management-plan.md:1747-1809`。

**问题说明：**

计划只处理了“取消后移除 timeout”和“worker late result 抑制”，但没有定义请求级原子终态。以下竞态仍不清晰：

- timeout 已触发并发送 TIMEOUT，worker 随后发送 SUCCESS；
- cancel 与 worker 完成同时发生；
- cancel 与 timeout 同时发生；
- `dispatchAndClose` 已关闭 Trace 后，worker 再次 dispatch。

**建议修订：**

引入 `ResponseState` 或在 `ActiveRequestRegistry` 中维护原子状态机：

`RUNNING -> COMPLETED | CANCELLED | TIMEOUT | FAILED`

所有 timeout runnable、cancel AIDL、worker success/error 都必须通过同一个 `tryComplete(requestId, terminalState)` 抢占终态。只有抢占成功的一方允许发送 listener 响应、关闭 trace、移除 timeout。

### 5. `SessionManager.endSession()` 的兼容路径仍可能与多用户 active session 设计冲突

**证据：**

- 当前 `SessionManager` 是全局 `currentUserId/currentSessionId`：`app/src/main/java/com/hirain/aiagent/memory/SessionManager.java:26-27`。
- 计划把状态改为 `activeSessions` map，但仍提到 `endSession()` 必须同步维护：`docs/plan/agent-request-conversation-management-plan.md:756-843`。

**问题说明：**

计划没有明确给出 `endSession(String userId)` 或 `UserMemoryContext.endSession()` 的改造闭环。若保留无参 `endSession()` 依赖 `currentUserId`，则 shutdown、ClearChatMemory 或多用户路径可能结束错误用户的 session，或者只结束最后一次访问用户。

**建议修订：**

- 新增并强制内部使用 `endSession(String userId)`。
- 无参 `endSession()` 只作为旧兼容入口，并明确只能用于当前默认用户路径。
- `MemoryOrchestrator.shutdown()`、`UserMemoryContext.endSession()` 必须按各自 `userId` 调用带参方法。

### 6. 会话 ID 仍使用秒级时间戳，快速创建会话有碰撞风险

**证据：**

- 计划 `createConversationSession()` 仍使用 `S_yyyyMMdd_HHmmss`：`docs/plan/agent-request-conversation-management-plan.md:798-812`。
- 当前 `sessions` 主键是 `(user_id, session_id)`：`app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java:176-185`。
- 计划 `createActiveSession()` 使用 `INSERT OR REPLACE`：`docs/plan/agent-request-conversation-management-plan.md:689-708`。

**问题说明：**

同一用户一秒内创建多个对话时，`sessionId` 可能重复。结合 `INSERT OR REPLACE`，可能覆盖原 session 行，导致消息统计、压缩统计、active 状态被重置。

**建议修订：**

- sessionId 改为 `S_<yyyyMMdd_HHmmss_SSS>_<shortUuid>` 或纯 UUID。
- `createActiveSession()` 避免 `INSERT OR REPLACE`，改为 `INSERT`/`INSERT OR IGNORE` + 碰撞重试。
- 对外部传入 sessionId 的场景，重复时应返回已存在或失败，不应静默覆盖。

### 7. `ConversationInfo.title/personaId` 没有持久化，列表与重启后会丢失信息

**证据：**

- `createConversation()` 从 request 读取 `personaId/title` 返回：`docs/plan/agent-request-conversation-management-plan.md:1023-1031`。
- `listConversations()` 统一使用 `DEFAULT_PERSONA_ID` 和 `null title`：`docs/plan/agent-request-conversation-management-plan.md:1033-1038`。
- `toConversationInfo()` 在 title 为空时写死“新对话”：`docs/plan/agent-request-conversation-management-plan.md:1097-1115`。

**问题说明：**

外部 App 创建对话时传入的标题和 persona 只存在于当次返回对象中。列表查询、进程重启、再次切换后都会退回默认值。这会让 UI 的对话列表和“AI 性格切换”状态不可持续。

**建议修订：**

- 若本阶段要求支持会话列表展示，应给 `sessions` 表增加 `title/persona_id/source_app/updated_at` 等元数据，或新增 conversation metadata 表。
- 若暂不持久化，应在计划中明确 title/persona 只是临时回显，不作为真实会话属性。但这会削弱外部 App 对话管理能力。

### 8. “AI 性格切换”目前只是协议预留，不是功能实现

**证据：**

- 计划只开放 `supportedTextPersonas = setOf("chat")`：`docs/plan/agent-request-conversation-management-plan.md:1474-1503`。
- unsupported persona fallback 到 `chat`：`docs/plan/agent-request-conversation-management-plan.md:1490-1500`。

**问题说明：**

用户目标包含“AI 性格切换等功能”，但计划实际只做 `personaId` 透传、trace/log 记录和 fallback。严格说，这不是 AI 性格切换实现。

**建议修订：**

将目标措辞改成“预留 personaId 协议并打通 Runtime 上下文”，或补充至少一个真实 persona 的 Prompt/AgentConfig 接入方案。否则验收时容易产生预期偏差。

---

## P2 中低风险问题

### 9. `conversationAction/targetRequestId` 增加了协议复杂度，但当前没有真实行为

计划新增 `conversationAction`、`targetRequestId`：`docs/plan/agent-request-conversation-management-plan.md:86-91`、`283-296`。但取消请求已经走独立 AIDL，计划也没有使用 `ACTION_INTERRUPT` 执行任何逻辑。

建议删除这两个字段，或明确标注为“不在本阶段 SDK 对外承诺的实验字段”。否则外部 App 可能误以为可以通过 `processAgentRequest()` 发控制动作。

### 10. Runtime Trace `client_message.id` 方法可能成为死代码

计划定义 `writeRequestMetaToTrace()`：`docs/plan/agent-request-conversation-management-plan.md:1917-1928`，但没有明确要求在 `AgentRuntime.startSession()` 创建 `RequestSession` 后调用。

建议在 Task 5.2 中补一句：`startSession()` 在 `sessionFactory.create(...)` 后立即调用 `writeRequestMetaToTrace(traceContext, session)`，并补单测或 trace mock 验证。

### 11. Phase 4 与 Phase 5 的 `RuntimeResult` 元信息改造顺序不够稳定

Phase 4 先新增 `RuntimeResult.cancelled(requestId, sessionId, reason, timestamp)`：`docs/plan/agent-request-conversation-management-plan.md:1708-1726`，Phase 5 再把 `userId/personaId/clientMessageId` 加入所有工厂：`docs/plan/agent-request-conversation-management-plan.md:1843-1888`。

建议在 Phase 4 引入 `cancelledResult` 时就使用最终签名，避免阶段内测试和实现反复改同一组工厂方法。

### 12. AIDL 管理接口同步执行 SQLite，需要明确 Binder 线程风险边界

计划选择同步返回轻量 AIDL 管理接口：`docs/plan/agent-request-conversation-management-plan.md:148-150`，并在 Binder 方法中直接调用 `conversationManager`：`docs/plan/agent-request-conversation-management-plan.md:1138-1160`。

短列表查询问题不大，但创建、删除、切换都涉及 SQLite 写事务。建议文档补充边界：单次最多 50 条列表、避免在 Binder 线程执行耗时清理、删除大量历史消息时后续迁移到异步或分页。

---

## 建议修订顺序

1. 先修正 P0：补上“真实短期 ChatMemory 按 userId/sessionId 隔离”的设计，否则会话管理功能不可验收。
2. 再修正 P1：Parcelable 兼容声明、请求终态状态机、SessionManager 多用户 endSession、sessionId 生成、conversation metadata 持久化。
3. 最后处理 P2：清理未使用协议字段、Trace 方法调用点、RuntimeResult 改造顺序、Binder 同步边界。

---

## 总体评价

这版计划比上一版更完整，已经正确识别了 Runtime 边界、`userId/sessionId` 分离、requestId/trace 一致性、VOICE 不迁移等方向。但它仍把“会话管理”主要理解成 `SessionMemoryStore` 的元数据增删改查，而不是“LLM 实际上下文隔离”。对于外部对话 App 来说，真正关键的是新建、切换、删除对话后模型看到的短期历史是否改变。当前计划没有打通这一点，因此实施前必须先修订。
