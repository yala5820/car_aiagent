# Agent Request Conversation Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为外部对话 App 补齐 AIAgent 的会话管理、请求取消、主请求协议扩展、用户切换与第一版 TEXT Persona 切换能力；其中“新建/切换/删除对话”必须真正影响 LLM 可见的短期上下文，而不只是维护会话元数据。

**Architecture:** 本阶段以 AIDL 协议、Runtime 边界和真实短期记忆绑定为主：`AIAgentService` 继续负责 Binder、listener、timeout、VOICE/IMAGE/CONTROL 包装；`AgentRuntime` 负责规范化 `AgentRequest`、生成 `RequestSession`、透传 `userId/sessionId/personaId`、处理协作式取消结果。会话管理复用并扩展 `SessionMemoryStore`，并通过 `SessionChatMemoryProvider` 让 `AgentLoopOrchestrator` 每次执行按 `userId + sessionId + personaId` 选择真实 `ChatMemory`，避免所有 TEXT 对话共享固定 `"ChatMemory"`。

**Tech Stack:** Android AIDL + Java Parcelable + Kotlin Service + Java Runtime + SQLite SessionMemoryStore + JUnit local unit tests.

---

## 0. 审查后修订要点

本计划已根据 `docs/review/conversation-management-plan-review.md`、`docs/review/conversation-management-plan-review-v2.md` 和 `docs/review/agent-request-conversation-management-plan-feasibility-review.md` 重新复盘，并补齐审查中指出的中低危问题：

- 修正 `SessionManager` 全局单例状态问题：Phase 2 必须把 active session 状态改为按 `userId` 隔离，并先写 `switchSession_crossUserDoesNotCorruptOtherUser` 失败测试。
- 简化 `AgentRuntime.startSession()` 与 `RequestSessionFactory.create()` 的 persona 传递：`RequestSessionFactory` 只从 `AgentRequest` 读取 `personaId`，不再接收冗余的独立 `personaId` 参数。
- 补齐 `ConversationManager.toConversationInfo()` 方法体，明确 `active/messageCount/tokenEstimate/compressionCount/endedAt` 的映射规则。
- 补齐 `ActiveRequestRegistryTest.newTestSession()` 测试辅助方法，避免测试代码引用未定义函数。
- 移除 `RuntimeResult` 二次包装备选方案，统一为直接扩展 `RuntimeResult` 构造函数和工厂方法。
- 明确 Trace 元数据策略：已有 root span 上的 `request.id/session.id/user.id/agent.persona` 由 `TraceManager.startAgentRequest()` 写入，Runtime 只补充 `client_message.id`，不重复写一套 `agent.request.id/agent.session.id/agent.user.id`。
- 补齐 feasibility review 指出的 P0：新增真实短期 `ChatMemory` 会话隔离阶段，`ConversationManager` 的 create/switch/delete 必须影响 `AgentLoopOrchestrator` 实际读取的历史消息。
- 明确 `AgentRequest` Parcelable 变更只保证同版本 SDK/Service 协同升级，不承诺旧二进制调用方与新 Service 混跑兼容。
- 请求取消改为统一终态状态机，所有 SUCCESS / CANCELLED / TIMEOUT / FAILED 响应都必须通过同一个 `tryComplete` 抢占终态。
- `sessionId` 改为毫秒时间 + short UUID，避免秒级时间戳碰撞；会话元数据持久化 `title/persona_id/source_app/updated_at`，避免列表重启后丢失标题和 persona。

额外保留的有效修订：

- `AIAgentService` 在创建 Trace 前先补齐缺失 `requestId`，避免 trace root requestId 与 Runtime 生成的 requestId 不一致。
- 对话切换只切换 `is_active`，不把旧对话写入 `ended_at`；`ended_at` 只表示真正结束。
- `ConversationManager` 通过 `ConversationSessionGateway` 做可测试封装，JVM 单测用 fake gateway，不依赖 Android SQLite。
- 取消请求若保留 `ALREADY_FINISHED` 状态，必须维护短期 finished cache。

---

## 1. 当前状态确认

### 1.1 已有能力

- `IAIAgentAidlInterface` 当前只有：
  - `processAgentRequest(in AgentRequest request)`
  - `registerListener(IAIAgentAidlListener listener)`
  - `unregisterListener(IAIAgentAidlListener listener)`
- `AgentRequest` 当前已有字段：
  - `requestId`
  - `sessionId`
  - `sourceApp`
  - `text`
  - `inputType`
  - `sceneType`
  - `imagePath`
  - `extraContext`
  - `timestamp`
- `AgentResponse` 当前已有字段：
  - `requestId`
  - `sessionId`
  - `success`
  - `text`
  - `errorType`
  - `timestamp`
- `SessionMemoryStore` 已经支持：
  - `createSession(userId, sessionId)`
  - `endSession(userId, sessionId)`
  - `getActiveSession(userId)`
  - `listSessions(userId)`
  - `deleteMessages(memoryId)`
- `MemoryOrchestrator` 已经支持：
  - `startNewSession(userId)`
  - 按 `userId` 管理 `UserMemoryContext`
- `AgentRuntime` 已经接管 TEXT 主链路，且有 `RequestSession`、`RuntimeResult`、`RuntimeResponseMapper`。
- 当前 `AIAgentService` 创建 TraceSession 早于 Runtime 规范化 requestId；如果外部请求没有传 `requestId`，可能出现 Trace 记录 ID 与最终响应 ID 不一致。
- 当前 `SessionManager` 只有单个 `currentUserId/currentSessionId`，而 `MemoryOrchestrator` 以 userId 管理多个 `UserMemoryContext`；这不适合新增多用户对话管理接口，必须在本阶段修正。
- 当前 `AgentLoopOrchestrator` 在构造期创建单个 `private final ChatMemory chatMemory`，chat persona 固定 `.chatMemoryStoreId("ChatMemory")`。这意味着只修改 `SessionMemoryStore.sessions` 元数据不会改变 LLM 实际可见的短期历史；本计划必须把真实 `ChatMemory` 选择接入主 TEXT 链路。

### 1.2 需要修正的关键问题

- `RequestSessionFactory` 当前把 `sessionId` 当作 `userId` 后备来源：
  - 当前逻辑：`String userId = nonEmpty(sessionId, "default_user");`
  - 目标逻辑：`userId` 从 `AgentRequest.userId` 读取，缺失时为 `default_user`；`sessionId` 与 `userId` 必须分离。
- Runtime 当前固定 `CHAT_PERSONA = "chat"`，外部无法指定 AI 人格。
- AIDL 目前没有对话创建、对话列表、对话删除、对话切换、取消请求接口。
- 现有 LLM 调用没有可证明的硬中断能力，因此本阶段取消请求按“协作式取消 + late result 抑制 + 统一取消响应”实现，不承诺直接中断底层 HTTP/模型调用。

---

## 2. 本阶段工作边界

### 2.1 本阶段包含

- 扩展 `AgentRequest`：
  - 新增 `userId`
  - 新增 `personaId`
  - 新增 `clientMessageId`，供外部 App 幂等与 UI 去重使用
- 扩展 `AgentResponse`：
  - 新增 `userId`
  - 新增 `personaId`
  - 新增 `status`
  - 新增 `errorDetail`
  - 新增 `clientMessageId`
- 新增会话管理 AIDL：
  - 创建对话
  - 列出用户对话
  - 删除对话
  - 切换活跃对话
  - 查询当前活跃对话
- 新增取消请求 AIDL：
  - 按 `requestId` 取消当前运行中的请求
  - 返回统一取消结果
- Runtime 接入：
  - `RequestSession` 正确保存 `userId/sessionId/personaId/clientMessageId`
  - `orchestratorContext` 写入 `user_id/session_id/persona_id/client_message_id`
  - `RuntimeResult` 支持 `CANCELLED`
  - `RuntimeResponseMapper` 映射取消响应
- AgentLoop 接入：
  - TEXT 主链路按 `userId + sessionId + personaId` 选择真实 `ChatMemory`
  - create/switch/delete conversation 后，LLM 可见短期历史必须随 session 改变
- Service 接入：
  - TEXT 请求使用新字段
  - VOICE 仅在“转成文本后的 Agent 对话上下文”中透传 `userId/personaId`，不迁移 VOICE ASR/TTS 主流程
  - IMAGE/CONTROL 保持原逻辑
- 单元测试与手动验收清单。

### 2.2 本阶段不包含

- 不重写 `AgentLoopOrchestrator` 主循环。
- 不改变 `ToolRegistry`、`ToolDispatcher`、`PromptManager`、`VehicleStateMachine` 的既有行为。
- 不改变 `MemoryOrchestrator` 的记忆提取、压缩、长期记忆注入策略；但允许为会话管理补充查询/切换/删除门面，并修正多用户 active session 状态。
- 不接入 `ContextOrchestrator`、`PolicyEngine`、`Eval`。
- 不实现长期记忆的完整用户画像拼接策略，后续 Context 阶段处理。
- 不实现真正流式输出。
- 不保证底层 LLM HTTP 请求可立即硬中断；本阶段取消为协作式取消和 late result 抑制。
- 不迁移 IMAGE / CONTROL 到 Runtime。
- 不把 ToolGroup 选择结果用于限制工具可见性。
- 不承诺旧版外部 SDK/JAR 与新版 Service 的 Parcelable 二进制跨版本兼容；本阶段要求外部 App 使用同版本 AIAgentSdk/AIDL 重新编译。

---

## 3. 设计决策

### 3.1 `AgentRequest` 是否臃肿

本阶段不拆分 `AgentRequest` 为多套 Parcelable。原因：

- `processAgentRequest(AgentRequest)` 是主对话入口，外部 App 接入成本已经围绕该类型建立。
- 本阶段新增字段都属于“请求元信息”，包括 `userId/personaId/clientMessageId`，仍然适合放在主请求体。
- 会话管理和取消请求使用独立 AIDL 方法，不把所有控制动作塞进 `AgentRequest`。

约束：

- `AgentRequest` 只承载一次 Agent 请求所需元数据，不承载会话列表、历史消息、Persona 配置详情。
- 会话管理返回使用独立 Parcelable。
- 取消请求返回使用独立 Parcelable。
- 不在本阶段新增 `conversationAction/targetRequestId`；控制类动作继续使用独立 AIDL，避免外部 App 误以为 `processAgentRequest()` 可承载取消或会话控制命令。

### 3.2 AIDL 接口风格

采用同步返回轻量结果的 AIDL 管理接口：

```aidl
interface IAIAgentAidlInterface {
    void processAgentRequest(in AgentRequest request);
    ConversationOperationResult createConversation(in ConversationRequest request);
    ConversationListResponse listConversations(String userId);
    ConversationOperationResult deleteConversation(String userId, String sessionId);
    ConversationOperationResult switchConversation(String userId, String sessionId);
    ConversationInfo getActiveConversation(String userId);
    CancelRequestResult cancelAgentRequest(String requestId, String reason);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}
```

原因：

- 会话管理是短耗时本地 SQLite 操作，适合同步返回。
- Agent 对话仍然走 `listener.onAIResponse` 异步返回。
- 取消请求需要外部 App 立即知道是否找到目标请求，所以同步返回 `CancelRequestResult`。

Binder 线程边界：

- `listConversations` 第一版最多返回最近 50 条会话，后续如需历史全量列表再引入分页参数。
- `create/switch/delete` 只允许执行短事务；删除会话时同步删除该会话的短期消息，若后续真实历史量变大，需迁移为异步清理或后台任务。
- AIDL 管理接口不做长期记忆重算、摘要重建、向量检索等耗时操作。

### 3.3 取消语义

取消请求分三种结果：

- `ACCEPTED`：找到运行中请求，已标记取消；后续 late result 会被抑制。
- `NOT_FOUND`：没有找到该 `requestId` 的运行中请求，可能已完成或 ID 错误。
- `ALREADY_FINISHED`：请求刚完成，取消没有实际生效。

TEXT 请求取消后的用户可见结果：

- 如果取消发生在结果返回前，listener 收到一条 `AgentResponse`：
  - `success=false`
  - `status="CANCELLED"`
  - `errorType="CANCELLED"`
  - `text="系统: 请求已取消"`
- 如果底层 LLM 后续返回结果，Service 不再把 late success 分发给 listener。

VOICE 请求取消：

- 本阶段只支持停止 TTS/语音播放和抑制后续文本结果，不承诺中断 ASR 或模型调用。

---

## 4. 文件结构规划

### 4.1 AIDL 与 Parcelable

- Modify: `app/src/main/aidl/com/hirain/aiagent/IAIAgentAidlInterface.aidl`
  - 增加会话管理和取消请求方法。
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationRequest.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationInfo.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationListResponse.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationOperationResult.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/CancelRequestResult.aidl`
- Modify: `app/src/main/java/com/hirain/aiagent/AgentRequest.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AgentResponse.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationRequest.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationInfo.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationListResponse.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationOperationResult.java`
- Create: `app/src/main/java/com/hirain/aiagent/CancelRequestResult.java`

### 4.2 会话管理

- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationManager.java`
  - 面向 AIDL 的会话门面。
- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationConstants.java`
  - 统一结果码、默认标题、默认 persona。
- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationSessionGateway.java`
  - 抽象会话读写能力，方便 `ConversationManager` 做 JVM 单测。
- Create: `app/src/main/java/com/hirain/aiagent/conversation/MemoryConversationSessionGateway.java`
  - 生产实现，委托 `MemoryOrchestrator` / `SessionMemoryStore`。
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
  - 增加只读查询/删除/切换所需公开方法，不改变现有记忆处理行为。
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java`
  - 将 active session 状态从单个字段修正为按 `userId` 隔离，并增加 `switchSession(userId, sessionId)`。
- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
  - 增加 `getSession(userId, sessionId)`、`createActiveSession(userId, sessionId)`、`activateSession(userId, sessionId)`、`deleteSession(userId, sessionId)`。
  - DB_VERSION 升级，持久化 `title/persona_id/source_app/updated_at/is_active` 等会话元数据。
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`
  - 根据 `userId/sessionId/personaId` 创建真实 `MessageWindowChatMemory`，底层使用 `SessionMemoryStore` 作为 `ChatMemoryStore`。
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryIds.java`
  - 统一生成 `memoryId = userId + "_" + sessionId + "_" + personaId`，避免各处拼接不一致。

### 4.3 Runtime 与取消

- Create: `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequestRegistry.java`
  - 保存运行中请求、取消状态与请求元信息。
- Create: `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequest.java`
  - 不可变请求快照 + 可变取消标记。
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
  - `startSession` 读取 `request.personaId`。
  - `execute` 前后检查取消标记。
  - 增加 `cancelledResult(session, reason)`。
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSession.java`
  - 增加 `clientMessageId`。
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
  - 规范化 `userId/personaId/clientMessageId`。
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`
  - 增加 `cancelled` 工厂方法。
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
  - 映射 `CANCELLED`，并写入 `userId/personaId/clientMessageId`。
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
  - 增加 `CLIENT_MESSAGE_ID = "client_message.id"`，避免 Runtime 重复写已有 root span 元数据。
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
  - 将构造期固定 `chatMemory` 改为每次 `execute` 按上下文选择 `ChatMemory`。
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
  - 增加第一版 TEXT persona 配置工厂，支持 `chat/friendly/concise` 等真实 prompt 差异。
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
  - 增加 TEXT persona prompt 常量。
- Create: `app/src/main/assets/prompts/system/assistant_friendly.txt`
- Create: `app/src/main/assets/prompts/system/assistant_concise.txt`

### 4.4 Service 接入

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
  - 初始化 `ConversationManager` 和 `ActiveRequestRegistry`。
  - 实现新增 AIDL 方法。
  - TEXT 请求注册 active request，取消时抑制 late result。
  - Trace 创建前先补齐缺失 `requestId`，Trace userId 使用 `request.userId ?: default_user`。
  - VOICE 文本对话上下文透传 `user_id/persona_id`。
  - 保持 IMAGE/CONTROL 原行为。

### 4.5 测试

- Create: `app/src/test/java/com/hirain/aiagent/runtime/ActiveRequestRegistryTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/conversation/ConversationManagerTest.java`
- Create: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`
- Create: `app/src/androidTest/java/com/hirain/aiagent/memory/SessionMemoryStoreInstrumentedTest.java`
- Create: `docs/check_accept/conversation-request-protocol-manual-checklist.md`

---

## 5. Phase 1：协议扩展与兼容性测试

**目标：** 先扩展 Parcelable/AIDL 类型，保证工程能编译，旧调用方不设置新字段时行为不变。

### Task 1.1：扩展 `AgentRequest`

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AgentRequest.java`

- [ ] **Step 1: 新增常量和字段**

在 `AgentRequest` 中新增：

```java
private String userId;
private String personaId;
private String clientMessageId;
```

- [ ] **Step 2: 修改 Parcel 读取顺序**

在现有字段读取之后追加读取，避免破坏旧字段顺序：

```java
userId = in.readString();
personaId = in.readString();
clientMessageId = in.readString();
```

- [ ] **Step 3: 修改 Parcel 写入顺序**

在现有字段写入之后追加写入：

```java
dest.writeString(userId);
dest.writeString(personaId);
dest.writeString(clientMessageId);
```

- [ ] **Step 4: 新增 getter/setter**

```java
public String getUserId() { return userId; }
public void setUserId(String userId) { this.userId = userId; }

public String getPersonaId() { return personaId; }
public void setPersonaId(String personaId) { this.personaId = personaId; }

public String getClientMessageId() { return clientMessageId; }
public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
```

兼容性说明：当前 `AgentRequest` 没有 parcel version 和 size 边界，本阶段只承诺“同版本 SDK/Service 中旧调用代码不设置新字段时行为不变”；不承诺旧版二进制 SDK 与新版 Service 混跑。

### Task 1.2：扩展 `AgentResponse`

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AgentResponse.java`

- [ ] **Step 1: 新增字段**

```java
private String userId;
private String personaId;
private String status;
private String errorDetail;
private String clientMessageId;
```

- [ ] **Step 2: Parcel 末尾追加读写**

读取：

```java
userId = in.readString();
personaId = in.readString();
status = in.readString();
errorDetail = in.readString();
clientMessageId = in.readString();
```

写入：

```java
dest.writeString(userId);
dest.writeString(personaId);
dest.writeString(status);
dest.writeString(errorDetail);
dest.writeString(clientMessageId);
```

- [ ] **Step 3: 新增 getter/setter**

```java
public String getUserId() { return userId; }
public void setUserId(String userId) { this.userId = userId; }

public String getPersonaId() { return personaId; }
public void setPersonaId(String personaId) { this.personaId = personaId; }

public String getStatus() { return status; }
public void setStatus(String status) { this.status = status; }

public String getErrorDetail() { return errorDetail; }
public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

public String getClientMessageId() { return clientMessageId; }
public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
```

### Task 1.3：新增会话与取消 Parcelable

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/ConversationRequest.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationInfo.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationListResponse.java`
- Create: `app/src/main/java/com/hirain/aiagent/ConversationOperationResult.java`
- Create: `app/src/main/java/com/hirain/aiagent/CancelRequestResult.java`

- [ ] **Step 1: `ConversationRequest` 字段**

```java
private String userId;
private String sessionId;
private String personaId;
private String title;
private String sourceApp;
private long timestamp;
```

- [ ] **Step 2: `ConversationInfo` 字段**

```java
private String userId;
private String sessionId;
private String personaId;
private String title;
private boolean active;
private long createdAt;
private long updatedAt;
private long endedAt;
private int messageCount;
private int tokenEstimate;
private int compressionCount;
```

约定：`endedAt=0` 表示该对话尚未结束；`active` 必须来自 `sessions.is_active`，不能用 `endedAt == 0` 推断。

- [ ] **Step 3: `ConversationListResponse` 字段**

```java
private boolean success;
private String errorType;
private String errorDetail;
private String userId;
private java.util.ArrayList<ConversationInfo> conversations;
```

- [ ] **Step 4: `ConversationOperationResult` 字段**

```java
private boolean success;
private String operation;
private String errorType;
private String errorDetail;
private ConversationInfo conversationInfo;
```

同时定义工厂方法：

```java
public static ConversationOperationResult success(String operation, ConversationInfo info) {
    ConversationOperationResult result = new ConversationOperationResult();
    result.setSuccess(true);
    result.setOperation(operation);
    result.setConversationInfo(info);
    return result;
}

public static ConversationOperationResult failure(String operation, String errorType, String errorDetail) {
    ConversationOperationResult result = new ConversationOperationResult();
    result.setSuccess(false);
    result.setOperation(operation);
    result.setErrorType(errorType);
    result.setErrorDetail(errorDetail);
    return result;
}
```

`ConversationListResponse` 同步定义：

```java
public static ConversationListResponse success(String userId, ArrayList<ConversationInfo> conversations) {
    ConversationListResponse response = new ConversationListResponse();
    response.setSuccess(true);
    response.setUserId(userId);
    response.setConversations(conversations);
    return response;
}

public static ConversationListResponse failure(String userId, String errorType, String errorDetail) {
    ConversationListResponse response = new ConversationListResponse();
    response.setSuccess(false);
    response.setUserId(userId);
    response.setErrorType(errorType);
    response.setErrorDetail(errorDetail);
    response.setConversations(new ArrayList<>());
    return response;
}
```

- [ ] **Step 5: `CancelRequestResult` 字段**

```java
public static final String STATUS_ACCEPTED = "ACCEPTED";
public static final String STATUS_NOT_FOUND = "NOT_FOUND";
public static final String STATUS_ALREADY_FINISHED = "ALREADY_FINISHED";

private boolean success;
private String requestId;
private String status;
private String reason;
private long timestamp;
```

同时定义工厂方法：

```java
public static CancelRequestResult accepted(String requestId, String reason, long timestamp) {
    CancelRequestResult result = new CancelRequestResult();
    result.setSuccess(true);
    result.setRequestId(requestId);
    result.setStatus(STATUS_ACCEPTED);
    result.setReason(reason);
    result.setTimestamp(timestamp);
    return result;
}

public static CancelRequestResult notFound(String requestId, String reason, long timestamp) {
    CancelRequestResult result = new CancelRequestResult();
    result.setSuccess(false);
    result.setRequestId(requestId);
    result.setStatus(STATUS_NOT_FOUND);
    result.setReason(reason);
    result.setTimestamp(timestamp);
    return result;
}

public static CancelRequestResult alreadyFinished(String requestId, String reason, long timestamp) {
    CancelRequestResult result = new CancelRequestResult();
    result.setSuccess(false);
    result.setRequestId(requestId);
    result.setStatus(STATUS_ALREADY_FINISHED);
    result.setReason(reason);
    result.setTimestamp(timestamp);
    return result;
}
```

### Task 1.4：新增 AIDL 声明

**Files:**

- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationRequest.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationInfo.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationListResponse.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/ConversationOperationResult.aidl`
- Create: `app/src/main/aidl/com/hirain/aiagent/CancelRequestResult.aidl`
- Modify: `app/src/main/aidl/com/hirain/aiagent/IAIAgentAidlInterface.aidl`

- [ ] **Step 1: 每个 Parcelable AIDL 文件内容**

```aidl
package com.hirain.aiagent;

parcelable ConversationRequest;
```

其他 Parcelable 文件同理，仅替换类名。

- [ ] **Step 2: 修改 `IAIAgentAidlInterface.aidl` import**

```aidl
import com.hirain.aiagent.ConversationRequest;
import com.hirain.aiagent.ConversationInfo;
import com.hirain.aiagent.ConversationListResponse;
import com.hirain.aiagent.ConversationOperationResult;
import com.hirain.aiagent.CancelRequestResult;
```

- [ ] **Step 3: 增加方法**

```aidl
ConversationOperationResult createConversation(in ConversationRequest request);
ConversationListResponse listConversations(String userId);
ConversationOperationResult deleteConversation(String userId, String sessionId);
ConversationOperationResult switchConversation(String userId, String sessionId);
ConversationInfo getActiveConversation(String userId);
CancelRequestResult cancelAgentRequest(String requestId, String reason);
```

### Task 1.5：Phase 1 测试

**Files:**

- No production code beyond protocol types.

- [ ] **Step 1: 编译 AIDL**

Run:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: 运行 Runtime 既有单测，确认协议扩展未破坏现有逻辑**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## 6. Phase 2：会话管理门面与 AIDL 实现

**目标：** 对外提供创建、列表、删除、切换、查询活跃对话能力，内部复用现有 Session 体系。

### Task 2.1：补齐 SessionMemoryStore 查询、元数据、激活与删除能力

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryStore.java`
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryIds.java`

- [ ] **Step 1: 先新增 `SessionMemoryIds`**

Phase 2 的 `deleteSession` 已经需要按 `userId/sessionId` 删除所有 persona 分支消息，因此 `SessionMemoryIds` 必须在本阶段创建，不能等到 Phase 3A：

```java
public final class SessionMemoryIds {
    private SessionMemoryIds() {}

    public static String build(String userId, String sessionId, String personaId) {
        String safeUser = isBlank(userId) ? "default_user" : userId;
        String safeSession = isBlank(sessionId) ? "default_session" : sessionId;
        String safePersona = isBlank(personaId) ? "chat" : personaId;
        return safeUser + "_" + safeSession + "_" + safePersona;
    }

    public static String buildPrefix(String userId, String sessionId) {
        String safeUser = isBlank(userId) ? "default_user" : userId;
        String safeSession = isBlank(sessionId) ? "default_session" : sessionId;
        return safeUser + "_" + safeSession + "_";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 2: 升级 sessions 表元数据**

将 `DB_VERSION` 提升到 2，并让 `sessions` 表持久化外部 App 所需的列表元数据：

```sql
title TEXT,
persona_id TEXT DEFAULT 'chat',
source_app TEXT,
updated_at INTEGER,
is_active INTEGER DEFAULT 1
```

`onUpgrade(1, 2)` 使用 `ALTER TABLE` 补列，不要直接 drop 表；只有开发期明确允许清库时才可使用破坏性迁移。

- [ ] **Step 3: 扩展 `SessionInfo`，显式携带 active 和元数据**

将 `SessionInfo` 增加 `boolean active` 字段，所有查询 SQL 都读取 `is_active`：

```java
public final String title;
public final String personaId;
public final String sourceApp;
public final long updatedAt;
public final boolean active;

public SessionInfo(String userId, String sessionId, long createdAt, Long endedAt,
                   int messageCount, int tokenEstimate, int compressionCount,
                   String title, String personaId, String sourceApp,
                   long updatedAt, boolean active) {
    this.userId = userId;
    this.sessionId = sessionId;
    this.createdAt = createdAt;
    this.endedAt = endedAt;
    this.messageCount = messageCount;
    this.tokenEstimate = tokenEstimate;
    this.compressionCount = compressionCount;
    this.title = title;
    this.personaId = personaId;
    this.sourceApp = sourceApp;
    this.updatedAt = updatedAt;
    this.active = active;
}
```

`readSessionInfo` 读取元数据列：

```java
return new SessionInfo(
        cursor.getString(0),
        cursor.getString(1),
        cursor.getLong(2),
        cursor.isNull(3) ? null : cursor.getLong(3),
        cursor.getInt(4),
        cursor.getInt(5),
        cursor.getInt(6),
        cursor.getString(7),
        cursor.getString(8),
        cursor.getString(9),
        cursor.getLong(10),
        cursor.getInt(11) == 1
);
```

- [ ] **Step 4: 新增 `getSession`**

```java
public SessionInfo getSession(String userId, String sessionId) {
    SQLiteDatabase db = dbHelper.getReadableDatabase();
    try (Cursor cursor = db.rawQuery(
            "SELECT user_id, session_id, created_at, ended_at, message_count, " +
                    "token_estimate, compression_count, title, persona_id, source_app, " +
                    "updated_at, is_active FROM sessions " +
                    "WHERE user_id = ? AND session_id = ? LIMIT 1",
            new String[]{userId, sessionId})) {
        if (cursor.moveToFirst()) {
            return readSessionInfo(cursor);
        }
    }
    return null;
}
```

- [ ] **Step 5: 新增 `createActiveSession`**

创建新对话时只把同用户其他会话置为 inactive，不写 `ended_at`：

```java
public boolean createActiveSession(String userId, String sessionId,
                                   String title, String personaId, String sourceApp) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    db.beginTransaction();
    try {
        db.execSQL("UPDATE sessions SET is_active = 0 WHERE user_id = ? AND is_active = 1",
                new Object[]{userId});
        long now = System.currentTimeMillis();
        db.execSQL(
                "INSERT INTO sessions (user_id, session_id, created_at, ended_at, title, " +
                        "persona_id, source_app, updated_at, is_active) " +
                        "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 1)",
                new Object[]{userId, sessionId, now, title, personaId, sourceApp, now});
        db.setTransactionSuccessful();
        return true;
    } catch (android.database.sqlite.SQLiteConstraintException duplicate) {
        return false;
    } finally {
        db.endTransaction();
    }
}
```

- [ ] **Step 6: 新增 `activateSession`**

```java
public boolean activateSession(String userId, String sessionId) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    SessionInfo existing = getSession(userId, sessionId);
    if (existing == null) {
        return false;
    }
    db.beginTransaction();
    try {
        long now = System.currentTimeMillis();
        db.execSQL("UPDATE sessions SET is_active = 0 WHERE user_id = ? AND is_active = 1",
                new Object[]{userId});
        db.execSQL("UPDATE sessions SET is_active = 1, ended_at = NULL, updated_at = ? " +
                        "WHERE user_id = ? AND session_id = ?",
                new Object[]{now, userId, sessionId});
        db.setTransactionSuccessful();
        return true;
    } finally {
        db.endTransaction();
    }
}
```

- [ ] **Step 7: 新增 `deleteSession`**

```java
public boolean deleteSession(String userId, String sessionId) {
    SQLiteDatabase db = dbHelper.getWritableDatabase();
    SessionInfo existing = getSession(userId, sessionId);
    if (existing == null) {
        return false;
    }
    String memoryIdPrefix = SessionMemoryIds.buildPrefix(userId, sessionId);
    db.beginTransaction();
    try {
        db.execSQL("DELETE FROM session_messages WHERE memory_id LIKE ?",
                new Object[]{memoryIdPrefix + "%"});
        db.execSQL("DELETE FROM sessions WHERE user_id = ? AND session_id = ?", new Object[]{userId, sessionId});
        db.setTransactionSuccessful();
        return true;
    } finally {
        db.endTransaction();
    }
}
```

### Task 2.2：修正 SessionManager 为按用户隔离 active session

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionManager.java`

- [ ] **Step 1: 用 Map 替代单个 currentSessionId**

新增内部状态：

```java
private static final class ActiveSessionState {
    final String sessionId;
    final long sessionStartTimeMs;

    ActiveSessionState(String sessionId, long sessionStartTimeMs) {
        this.sessionId = sessionId;
        this.sessionStartTimeMs = sessionStartTimeMs;
    }
}

private final ConcurrentHashMap<String, ActiveSessionState> activeSessions = new ConcurrentHashMap<>();
```

- [ ] **Step 2: 增加按用户读取方法**

```java
public String currentSessionId(String userId) {
    ActiveSessionState state = activeSessions.get(userId);
    return state != null ? state.sessionId : null;
}

public boolean hasActiveSession(String userId) {
    return currentSessionId(userId) != null;
}

public String currentMemoryId(String userId) {
    String sessionId = currentSessionId(userId);
    return sessionId != null ? SessionMemoryStore.buildMemoryId(userId, sessionId) : null;
}
```

- [ ] **Step 3: 新增 `createConversationSession`**

对话管理入口使用该方法，不复用会把旧会话写 `ended_at` 的 `startNewSession`：

```java
public String createConversationSession(String userId, String title,
                                        String personaId, String sourceApp) {
    for (int attempt = 0; attempt < 3; attempt++) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(new Date());
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionId = "S_" + timestamp + "_" + suffix;
        boolean created = store.createActiveSession(userId, sessionId, title, personaId, sourceApp);
        if (created) {
            activeSessions.put(userId, new ActiveSessionState(sessionId, System.currentTimeMillis()));
            Log.d(TAG, "Created conversation session " + sessionId + " for user " + userId);
            return sessionId;
        }
    }
    throw new IllegalStateException("Failed to create unique sessionId for user " + userId);
}
```

- [ ] **Step 4: 更新既有生命周期方法**

`getOrCreateSession(userId)`、`startNewSession(userId)`、`endSession(String userId)` 必须同步维护 `activeSessions`：

```java
public String getOrCreateSession(String userId) {
    currentUserId.set(userId);
    ActiveSessionState cached = activeSessions.get(userId);
    if (cached != null) {
        return cached.sessionId;
    }
    SessionMemoryStore.SessionInfo active = store.getActiveSession(userId);
    if (active != null) {
        activeSessions.put(userId, new ActiveSessionState(active.sessionId, active.createdAt));
        return active.sessionId;
    }
    return createConversationSession(userId, "新对话", "chat", "system");
}

public String startNewSession(String userId) {
    String oldSessionId = currentSessionId(userId);
    if (oldSessionId != null) {
        store.endSession(userId, oldSessionId);
    }
    return createConversationSession(userId, "新对话", "chat", "system");
}

public void endSession(String userId) {
    String sessionId = currentSessionId(userId);
    if (sessionId != null) {
        store.endSession(userId, sessionId);
        activeSessions.remove(userId);
    }
}

@Deprecated
public void endSession() {
    endSession(currentUserId.get());
}
```

说明：旧的 `startNewSession` 仍保留“结束旧会话”的语义，供现有 `ClearChatMemory` 等旧路径使用；新的会话管理 `createConversationSession` 不结束旧会话。

- [ ] **Step 5: 新增 `switchSession`**

```java
public boolean switchSession(String userId, String sessionId) {
    boolean activated = store.activateSession(userId, sessionId);
    if (!activated) {
        return false;
    }
    SessionMemoryStore.SessionInfo info = store.getSession(userId, sessionId);
    activeSessions.put(userId, new ActiveSessionState(
            sessionId,
            info != null ? info.createdAt : System.currentTimeMillis()));
    Log.d(TAG, "Switched session " + sessionId + " for user " + userId);
    return true;
}
```

保留原 `currentSessionId()` / `currentMemoryId()` 作为兼容方法时，只允许用于 `currentUserId` 指向的默认路径；新代码必须优先使用带 `userId` 的重载。

### Task 2.3：MemoryOrchestrator 暴露会话门面方法

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`

- [ ] **Step 1: 增加查询方法**

```java
public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
    return sessionStore.listSessions(userId);
}

public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
    return sessionStore.getActiveSession(userId);
}

public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
    return sessionStore.getSession(userId, sessionId);
}
```

- [ ] **Step 2: 增加切换和删除方法**

```java
public String createConversationSession(String userId, String title,
                                        String personaId, String sourceApp) {
    getUserContext(userId);
    String sessionId = sessionManager.createConversationSession(userId, title, personaId, sourceApp);
    Log.d(TAG, "Conversation session created for user " + userId + ": " + sessionId);
    return sessionId;
}

public boolean switchSession(String userId, String sessionId) {
    boolean switched = sessionManager.switchSession(userId, sessionId);
    if (switched) {
        Log.d(TAG, "Session switched for user " + userId + ": " + sessionId);
    }
    return switched;
}

public boolean deleteSession(String userId, String sessionId) {
    return sessionStore.deleteSession(userId, sessionId);
}
```

同时修改 `UserMemoryContext.currentSessionId()` 与 `UserMemoryContext.currentMemoryId()`：

```java
public String currentSessionId() { return sessionManager.currentSessionId(userId); }
public String currentMemoryId() { return sessionManager.currentMemoryId(userId); }
public void endSession() { sessionManager.endSession(userId); }
```

`MemoryOrchestrator.shutdown()` 不再调用无参 `SessionManager.endSession()`；必须遍历现有 `UserMemoryContext`，由每个 context 按自己的 `userId` 调用 `endSession()`，避免结束错误用户的 active session。

并修改 `MemoryOrchestrator.prepareSystemPrompt` 中的判断：

```java
if (!sessionManager.hasActiveSession(userId)) {
    ctx.initSession();
}
```

### Task 2.4：新增 ConversationManager

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationConstants.java`
- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationSessionGateway.java`
- Create: `app/src/main/java/com/hirain/aiagent/conversation/MemoryConversationSessionGateway.java`
- Create: `app/src/main/java/com/hirain/aiagent/conversation/ConversationManager.java`

- [ ] **Step 1: `ConversationConstants`**

```java
package com.hirain.aiagent.conversation;

public final class ConversationConstants {
    public static final String DEFAULT_USER_ID = "default_user";
    public static final String DEFAULT_PERSONA_ID = "chat";
    public static final String OP_CREATE = "CREATE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_SWITCH = "SWITCH";
    public static final String ERROR_INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";
    public static final String ERROR_INTERNAL = "INTERNAL";

    private ConversationConstants() {}
}
```

- [ ] **Step 2: 新增 `ConversationSessionGateway`**

```java
package com.hirain.aiagent.conversation;

import com.hirain.aiagent.memory.SessionMemoryStore;

import java.util.List;

public interface ConversationSessionGateway {
    String createConversationSession(String userId, String title, String personaId, String sourceApp);
    List<SessionMemoryStore.SessionInfo> listSessions(String userId);
    SessionMemoryStore.SessionInfo getActiveSession(String userId);
    SessionMemoryStore.SessionInfo getSession(String userId, String sessionId);
    boolean switchSession(String userId, String sessionId);
    boolean deleteSession(String userId, String sessionId);
}
```

- [ ] **Step 3: 新增 `MemoryConversationSessionGateway`**

```java
package com.hirain.aiagent.conversation;

import com.hirain.aiagent.memory.MemoryOrchestrator;
import com.hirain.aiagent.memory.SessionMemoryStore;

import java.util.List;

public class MemoryConversationSessionGateway implements ConversationSessionGateway {
    private final MemoryOrchestrator memoryOrchestrator;

    public MemoryConversationSessionGateway(MemoryOrchestrator memoryOrchestrator) {
        this.memoryOrchestrator = memoryOrchestrator;
    }

    @Override
    public String createConversationSession(String userId, String title,
                                            String personaId, String sourceApp) {
        return memoryOrchestrator.createConversationSession(userId, title, personaId, sourceApp);
    }

    @Override
    public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
        return memoryOrchestrator.listSessions(userId);
    }

    @Override
    public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
        return memoryOrchestrator.getActiveSession(userId);
    }

    @Override
    public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
        return memoryOrchestrator.getSession(userId, sessionId);
    }

    @Override
    public boolean switchSession(String userId, String sessionId) {
        return memoryOrchestrator.switchSession(userId, sessionId);
    }

    @Override
    public boolean deleteSession(String userId, String sessionId) {
        return memoryOrchestrator.deleteSession(userId, sessionId);
    }
}
```

- [ ] **Step 4: `ConversationManager` 核心方法**

```java
public ConversationOperationResult createConversation(ConversationRequest request) {
    String userId = normalize(request != null ? request.getUserId() : null, ConversationConstants.DEFAULT_USER_ID);
    String personaId = normalize(request != null ? request.getPersonaId() : null, ConversationConstants.DEFAULT_PERSONA_ID);
    String title = normalize(request != null ? request.getTitle() : null, "新对话");
    String sourceApp = normalize(request != null ? request.getSourceApp() : null, "unknown");
    String sessionId = sessionGateway.createConversationSession(userId, title, personaId, sourceApp);
    SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getSession(userId, sessionId);
    return ConversationOperationResult.success(
            ConversationConstants.OP_CREATE,
            toConversationInfo(sessionInfo, personaId, title));
}

public ConversationListResponse listConversations(String userId) {
    String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
    List<ConversationInfo> infos = sessionGateway.listSessions(normalizedUserId).stream()
            .map(info -> toConversationInfo(info, info.personaId, info.title))
            .collect(Collectors.toCollection(ArrayList::new));
    return ConversationListResponse.success(normalizedUserId, infos);
}
```

- [ ] **Step 5: 删除、切换、查询活跃会话**

```java
public ConversationOperationResult deleteConversation(String userId, String sessionId) {
    String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
    if (isBlank(sessionId)) {
        return ConversationOperationResult.failure(
                ConversationConstants.OP_DELETE,
                ConversationConstants.ERROR_INVALID_ARGUMENT,
                "sessionId 不能为空");
    }
    boolean deleted = sessionGateway.deleteSession(normalizedUserId, sessionId);
    if (!deleted) {
        return ConversationOperationResult.failure(
                ConversationConstants.OP_DELETE,
                ConversationConstants.ERROR_NOT_FOUND,
                "对话不存在");
    }
    ConversationInfo info = new ConversationInfo();
    info.setUserId(normalizedUserId);
    info.setSessionId(sessionId);
    info.setActive(false);
    return ConversationOperationResult.success(ConversationConstants.OP_DELETE, info);
}

public ConversationOperationResult switchConversation(String userId, String sessionId) {
    String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
    if (isBlank(sessionId)) {
        return ConversationOperationResult.failure(
                ConversationConstants.OP_SWITCH,
                ConversationConstants.ERROR_INVALID_ARGUMENT,
                "sessionId 不能为空");
    }
    boolean switched = sessionGateway.switchSession(normalizedUserId, sessionId);
    if (!switched) {
        return ConversationOperationResult.failure(
                ConversationConstants.OP_SWITCH,
                ConversationConstants.ERROR_NOT_FOUND,
                "对话不存在");
    }
    SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getSession(normalizedUserId, sessionId);
    return ConversationOperationResult.success(
            ConversationConstants.OP_SWITCH,
            toConversationInfo(sessionInfo, ConversationConstants.DEFAULT_PERSONA_ID, null));
}

public ConversationInfo getActiveConversation(String userId) {
    String normalizedUserId = normalize(userId, ConversationConstants.DEFAULT_USER_ID);
    SessionMemoryStore.SessionInfo sessionInfo = sessionGateway.getActiveSession(normalizedUserId);
    if (sessionInfo == null) {
        return null;
    }
    return toConversationInfo(sessionInfo, sessionInfo.personaId, sessionInfo.title);
}

private ConversationInfo toConversationInfo(SessionMemoryStore.SessionInfo sessionInfo,
                                            String personaId,
                                            String title) {
    if (sessionInfo == null) {
        return null;
    }
    ConversationInfo info = new ConversationInfo();
    info.setUserId(sessionInfo.userId);
    info.setSessionId(sessionInfo.sessionId);
    info.setPersonaId(normalize(personaId != null ? personaId : sessionInfo.personaId,
            ConversationConstants.DEFAULT_PERSONA_ID));
    info.setTitle(isBlank(title) ? normalize(sessionInfo.title, "新对话") : title);
    info.setActive(sessionInfo.active);
    info.setCreatedAt(sessionInfo.createdAt);
    info.setUpdatedAt(sessionInfo.updatedAt);
    info.setEndedAt(sessionInfo.endedAt != null ? sessionInfo.endedAt : 0L);
    info.setMessageCount(sessionInfo.messageCount);
    info.setTokenEstimate(sessionInfo.tokenEstimate);
    info.setCompressionCount(sessionInfo.compressionCount);
    return info;
}
```

### Task 2.5：AIAgentService 实现会话管理 AIDL

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 新增字段**

```kotlin
private lateinit var conversationManager: ConversationManager
```

- [ ] **Step 2: `onCreate` 初始化**

在 `memoryOrchestrator` 初始化之后：

```kotlin
conversationManager = ConversationManager(MemoryConversationSessionGateway(memoryOrchestrator))
```

- [ ] **Step 3: Binder 中实现新增方法**

```kotlin
override fun createConversation(request: ConversationRequest?): ConversationOperationResult {
    return conversationManager.createConversation(request)
}

override fun listConversations(userId: String?): ConversationListResponse {
    return conversationManager.listConversations(userId)
}

override fun deleteConversation(userId: String?, sessionId: String?): ConversationOperationResult {
    return conversationManager.deleteConversation(userId, sessionId)
}

override fun switchConversation(userId: String?, sessionId: String?): ConversationOperationResult {
    return conversationManager.switchConversation(userId, sessionId)
}

override fun getActiveConversation(userId: String?): ConversationInfo? {
    return conversationManager.getActiveConversation(userId)
}
```

### Task 2.6：Phase 2 测试

**Files:**

- Create: `app/src/test/java/com/hirain/aiagent/conversation/ConversationManagerTest.java`

- [ ] **Step 1: 使用 fake gateway，避免 JVM 单测依赖 Android SQLite**

测试类中定义：

```java
private static final class FakeConversationSessionGateway implements ConversationSessionGateway {
    private final Map<String, List<SessionMemoryStore.SessionInfo>> sessions = new HashMap<>();
    private int nextId = 1;

    @Override
    public String createConversationSession(String userId, String title,
                                            String personaId, String sourceApp) {
        String sessionId = "S_fake_" + nextId++;
        List<SessionMemoryStore.SessionInfo> list =
                sessions.computeIfAbsent(userId, ignored -> new ArrayList<>());
        list.replaceAll(info -> copyWithActive(info, false));
        SessionMemoryStore.SessionInfo info = new SessionMemoryStore.SessionInfo(
                userId, sessionId, 1000L + nextId, null, 0, 0, 0,
                title, personaId, sourceApp, 1000L + nextId, true);
        list.add(info);
        return sessionId;
    }

    @Override
    public List<SessionMemoryStore.SessionInfo> listSessions(String userId) {
        return sessions.getOrDefault(userId, List.of());
    }

    @Override
    public SessionMemoryStore.SessionInfo getActiveSession(String userId) {
        return listSessions(userId).stream().filter(info -> info.active).findFirst().orElse(null);
    }

    @Override
    public SessionMemoryStore.SessionInfo getSession(String userId, String sessionId) {
        return listSessions(userId).stream()
                .filter(info -> info.sessionId.equals(sessionId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean switchSession(String userId, String sessionId) {
        List<SessionMemoryStore.SessionInfo> list = sessions.get(userId);
        if (list == null || getSession(userId, sessionId) == null) {
            return false;
        }
        List<SessionMemoryStore.SessionInfo> updated = new ArrayList<>();
        for (SessionMemoryStore.SessionInfo info : list) {
            updated.add(copyWithActive(info, info.sessionId.equals(sessionId)));
        }
        sessions.put(userId, updated);
        return true;
    }

    @Override
    public boolean deleteSession(String userId, String sessionId) {
        return sessions.getOrDefault(userId, new ArrayList<>())
                .removeIf(info -> info.sessionId.equals(sessionId));
    }

    private SessionMemoryStore.SessionInfo copyWithActive(SessionMemoryStore.SessionInfo info,
                                                          boolean active) {
        return new SessionMemoryStore.SessionInfo(
                info.userId, info.sessionId, info.createdAt, info.endedAt,
                info.messageCount, info.tokenEstimate, info.compressionCount,
                info.title, info.personaId, info.sourceApp, info.updatedAt, active);
    }
}

private static ConversationRequest requestFor(String userId) {
    ConversationRequest request = new ConversationRequest();
    request.setUserId(userId);
    request.setPersonaId("chat");
    request.setTitle("测试对话");
    request.setSourceApp("unit-test");
    request.setTimestamp(1000L);
    return request;
}
```

- [ ] **Step 2: 单元测试覆盖点**

测试用例名称：

```java
createConversation_usesDefaultUserAndChatPersonaWhenMissing()
listConversations_returnsOnlyRequestedUserSessions()
switchConversation_returnsNotFoundForMissingSession()
deleteConversation_deletesSessionAndMessages()
switchSession_crossUserDoesNotCorruptOtherUser()
```

`switchSession_crossUserDoesNotCorruptOtherUser()` 的断言重点：

```java
@Test
public void switchSession_crossUserDoesNotCorruptOtherUser() {
    FakeConversationSessionGateway gateway = new FakeConversationSessionGateway();
    ConversationManager manager = new ConversationManager(gateway);
    ConversationInfo userA = manager.createConversation(requestFor("user-A")).getConversationInfo();
    ConversationInfo userB = manager.createConversation(requestFor("user-B")).getConversationInfo();

    manager.switchConversation("user-B", userB.getSessionId());

    assertEquals(userA.getSessionId(),
            manager.getActiveConversation("user-A").getSessionId());
    assertEquals(userB.getSessionId(),
            manager.getActiveConversation("user-B").getSessionId());
}
```

- [ ] **Step 3: 运行测试**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.conversation.*"
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## 7. Phase 3：用户切换与 Persona 透传

**目标：** 修正 `userId/sessionId` 混用问题，并允许外部 App 在 TEXT 请求中指定 `personaId`。

### Task 3.1：RequestSessionFactory 正确规范化 userId/personaId

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`

- [ ] **Step 1: 写失败测试**

新增测试：

```java
@Test
public void create_usesRequestUserIdInsteadOfSessionId() {
    AgentRequest request = new AgentRequest();
    request.setRequestId("req-1");
    request.setSessionId("session-123");
    request.setUserId("user-A");
    request.setPersonaId("warm");
    request.setClientMessageId("client-9");
    request.setInputType("TEXT");
    request.setText("你好");
    RequestSessionFactory factory = new RequestSessionFactory(() -> "generated", () -> 1000L);

    RequestSession session = factory.create(request, null, null,
            ToolGroupSelectionResult.fallback("test_default"));

    assertEquals("user-A", session.userId());
    assertEquals("session-123", session.sessionId());
    assertEquals("warm", session.personaId());
    assertEquals("client-9", session.clientMessageId());
    assertEquals("user-A", session.orchestratorContext().get("user_id"));
    assertEquals("warm", session.orchestratorContext().get("persona_id"));
    assertEquals("client-9", session.orchestratorContext().get("client_message_id"));
}
```

- [ ] **Step 2: 实现最小修改**

先简化 `create` 方法签名，移除冗余的 `personaId` 参数：

```java
public RequestSession create(AgentRequest request,
                             TraceContext traceContext,
                             IntentResult intentResult,
                             ToolGroupSelectionResult toolGroupSelectionResult) {
```

然后替换当前 userId/personaId 规范化逻辑：

```java
String userId = nonEmpty(request.getUserId(), "default_user");
String normalizedPersonaId = nonEmpty(request.getPersonaId(), "chat");
String clientMessageId = emptyToNull(request.getClientMessageId());
```

同步更新所有现有调用点：

- `RequestSessionFactoryTest` 中所有 `factory.create(request, "chat", traceContext, ...)` 调用删除第 2 个 `personaId` 参数。
- `AgentRuntime.startSession()` 中的 `sessionFactory.create(...)` 调用使用新签名。
- 任何新增测试不得再把 persona 作为独立参数传给 factory。

构建 context：

```java
context.put("user_id", userId);
if (sessionId != null) {
    context.put("session_id", sessionId);
}
context.put("persona_id", normalizedPersonaId);
if (clientMessageId != null) {
    context.put("client_message_id", clientMessageId);
}
```

- [ ] **Step 3: RequestSession 增加 clientMessageId**

```java
private final String clientMessageId;
public String clientMessageId() { return clientMessageId; }
```

构造函数参数和赋值同步补齐，建议把 `clientMessageId` 放在 `personaId` 与 `userInput` 之间，避免与文本输入混淆：

```java
RequestSession(AgentRequest request, String requestId, String sessionId,
               String userId, String sourceApp, String inputType,
               String personaId, String clientMessageId, String userInput,
               long startedAtMs, TraceContext traceContext,
               IntentResult intentResult,
               ToolGroupSelectionResult toolGroupSelectionResult,
               Map<String, Object> orchestratorContext) {
```

### Task 3.2：AgentRuntime 使用 request.personaId

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`

- [ ] **Step 1: 新增测试**

```java
@Test
public void startSession_preservesPersonaIdFromRequest() {
    AgentRequest request = new AgentRequest();
    request.setInputType("TEXT");
    request.setText("你好");
    request.setPersonaId("warm");
    AgentRuntime runtime = new AgentRuntime(
            (input, context) -> AgentResult.success("ok", 1, 10L),
            () -> "req-1",
            () -> 1000L);

    RequestSession session = runtime.startSession(request, null);

    assertEquals("warm", session.personaId());
    assertEquals("warm", session.orchestratorContext().get("persona_id"));
}
```

- [ ] **Step 2: 修改 `startSession`**

```java
return sessionFactory.create(request, traceContext, intentResult, toolGroupSelectionResult);
```

- [ ] **Step 3: 保留既有构造函数路径**

本阶段不新增 `AgentRuntime` 构造参数，也不移除既有构造函数。必须确认以下 3 参数构造函数仍存在并继续委托到完整默认链路，避免既有 Runtime 测试路径失效：

```java
public AgentRuntime(AgentExecutor chatExecutor,
                    IdGenerator idGenerator,
                    TimeProvider timeProvider) {
    this(chatExecutor, new KeywordIntentRouter(),
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry()),
            idGenerator, timeProvider);
}
```

### Task 3.3：Service Trace 与 VOICE 上下文使用规范化 requestId/userId/personaId

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 增加本地规范化函数**

```kotlin
private fun ensureRequestId(request: AgentRequest?): String {
    val existing = request?.requestId?.takeIf { it.isNotBlank() }
    if (existing != null) return existing
    val generated = java.util.UUID.randomUUID().toString()
    if (request != null) {
        request.requestId = generated
    }
    return generated
}

private fun normalizeUserId(request: AgentRequest?): String {
    return request?.userId?.takeIf { it.isNotBlank() } ?: "default_user"
}

private fun normalizePersonaId(request: AgentRequest?): String {
    return request?.personaId?.takeIf { it.isNotBlank() } ?: "chat"
}
```

- [ ] **Step 2: TEXT trace 使用规范化 requestId/userId/personaId**

将 `startAgentRequest` 的 persona/user 参数调整为：

```kotlin
val requestId = ensureRequestId(request)
val userId = normalizeUserId(request)
val personaId = normalizePersonaId(request)
val session = traceManager.startAgentRequest(
    personaId,
    userId,
    requestId,
    request.sessionId,
    request.sourceApp,
    request.inputType,
    message
)
```

这样 Runtime 后续会保留同一个 `request.requestId`，不会再生成另一个 response requestId。

- [ ] **Step 3: VOICE 文本对话上下文透传**

VOICE 中构造 `ctx` 时使用：

```kotlin
val userId = normalizeUserId(request)
val personaId = normalizePersonaId(request)
val ctx = mutableMapOf<String, Any>(
    "user_id" to userId,
    "persona_id" to personaId
)
ctx.putAll(session.toTraceContext().toContextData())
```

### Task 3.4：Persona 执行选择

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
- Create: `app/src/main/assets/prompts/system/assistant_friendly.txt`
- Create: `app/src/main/assets/prompts/system/assistant_concise.txt`

- [ ] **Step 1: 本阶段开放第一版真实 TEXT persona 白名单**

新增：

```kotlin
private val supportedTextPersonas = setOf("chat", "friendly", "concise")
```

`scene` 和 `vision_qa` 分别服务场景与视觉问答，不直接开放给普通 TEXT 对话。

- [ ] **Step 2: AgentConfigFactory 增加 TEXT persona 配置**

新增统一入口，避免 Service 直接拼 prompt 文件名：

```java
public static AgentConfig createTextPersona(Context context,
                                            PromptManager promptManager,
                                            MemoryOrchestrator memoryOrchestrator,
                                            ToolRegistry toolRegistry,
                                            VehicleStatusPreProcessor.VehicleStatusProvider statusProvider,
                                            VehicleSpeedManager speedManager,
                                            String personaId) {
    String normalized = normalizeTextPersona(personaId);
    String template = "friendly".equals(normalized)
            ? PromptConstants.SYSTEM_ASSISTANT_FRIENDLY
            : "concise".equals(normalized)
                ? PromptConstants.SYSTEM_ASSISTANT_CONCISE
                : PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
    return AgentConfig.builder(normalized)
            .modelName("qwen-turbo")
            .systemPromptTemplateName(template)
            .maxIterations(10)
            .maxMemoryMessages(50)
            .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
            .chatMemoryStoreId("ChatMemory_" + normalized)
            .preProcessors(List.of(
                    new MemoryPreProcessor(memoryOrchestrator),
                    new VehicleStatusPreProcessor(promptManager, statusProvider),
                    new TimeContextPreProcessor()))
            .modelCaller(new Lc4jModelCaller(buildQwenTurbo()))
            .toolExecutor(toolRegistry::dispatch)
            .toolSubset(null)
            .safetyGuards(List.of(
                    new SpeedBasedDoorLockGuard(() -> parseSpeed(speedManager.getSpeedStatus()))))
            .postProcessors(List.of(
                    new NoOpPostProcessor(),
                    new MemoryPostProcessor()))
            .terminator(new CompositeTerminator(
                    new NoToolCallTerminator(),
                    new SafetyVetoTerminator()))
            .resultCollector(new DirectTextCollector())
            .timeout(Duration.ofSeconds(30))
            .build();
}
```

- [ ] **Step 3: Service 为 TEXT persona 准备 Orchestrator 映射**

```kotlin
private lateinit var textOrchestrators: Map<String, AgentLoopOrchestrator>

private fun normalizeTextPersona(personaId: String?): String {
    val requested = personaId?.takeIf { it.isNotBlank() } ?: "chat"
    return if (supportedTextPersonas.contains(requested)) requested else "chat"
}

textOrchestrators = supportedTextPersonas.associateWith { persona ->
    val config = AgentConfigFactory.createTextPersona(
        this,
        promptManager!!,
        memoryOrchestrator,
        toolRegistry,
        statusProvider,
        speedManager,
        persona
    )
    AgentLoopOrchestrator(
        config,
        this,
        promptManager!!,
        memoryOrchestrator,
        toolRegistry.toolSpecifications
    )
}
```

- [ ] **Step 4: AgentRuntime executor 中按 persona 选择真实 Orchestrator**

```kotlin
agentRuntime = AgentRuntime { userInput, context ->
    val requestedPersona = context["persona_id"] as? String ?: "chat"
    val persona = normalizeTextPersona(requestedPersona)
    if (persona != requestedPersona) {
        Log.w(TAG, "Unsupported TEXT persona=$requestedPersona, fallback to chat")
    }
    val orchestrator = textOrchestrators[persona] ?: textOrchestrators.getValue("chat")
    val personaContext = HashMap(context)
    personaContext["persona_id"] = persona
    orchestrator.execute(userInput, personaContext)
}
```

说明：本阶段只做第一版固定内置 persona，不提供外部动态编辑 persona 配置。`friendly/concise` 的差异通过独立 system prompt 与 `AgentConfig.personaId` 落地；更复杂的人格市场、用户自定义人格和 Context 组合策略留到后续 Context 阶段。

### Task 3.5：Phase 3 测试

- [ ] **Step 1: 运行 Runtime 单测**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.runtime.*"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: 编译 Service**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## 7A. Phase 3A：真实短期 ChatMemory 会话隔离

**目标：** 让 `createConversation/switchConversation/deleteConversation` 不只改变会话元数据，还真正改变 TEXT 主链路 LLM 可见的短期历史。

### Task 3A.1：新增 SessionMemoryIds 与 SessionChatMemoryProvider

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/memory/SessionMemoryIds.java`
- Create: `app/src/main/java/com/hirain/aiagent/memory/SessionChatMemoryProvider.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`

- [ ] **Step 1: 复用 Phase 2 的统一 memoryId**

Phase 3A 不再重复创建 `SessionMemoryIds`，只复用 Phase 2 已创建的 `build(userId, sessionId, personaId)` 与 `buildPrefix(userId, sessionId)`。

- [ ] **Step 2: 定义 SessionChatMemoryProvider**

```java
package com.hirain.aiagent.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.concurrent.ConcurrentHashMap;

public class SessionChatMemoryProvider {
    private final SessionMemoryStore sessionStore;
    private final ConcurrentHashMap<String, ChatMemory> cache = new ConcurrentHashMap<>();

    public SessionChatMemoryProvider(SessionMemoryStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public ChatMemory get(String userId, String sessionId, String personaId, int maxMessages) {
        String memoryId = SessionMemoryIds.build(userId, sessionId, personaId);
        return cache.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(sessionStore)
                        .build());
    }

    public void clear(String userId, String sessionId, String personaId) {
        String memoryId = SessionMemoryIds.build(userId, sessionId, personaId);
        ChatMemory memory = cache.remove(memoryId);
        if (memory != null) {
            memory.clear();
        }
        sessionStore.deleteMessages(memoryId);
    }
}
```

- [ ] **Step 3: MemoryOrchestrator 暴露 provider 和 session 解析**

```java
private final SessionChatMemoryProvider chatMemoryProvider;

public SessionChatMemoryProvider chatMemoryProvider() {
    return chatMemoryProvider;
}

public String resolveSessionId(String userId, String requestedSessionId,
                               String title, String personaId, String sourceApp) {
    if (requestedSessionId != null && !requestedSessionId.isBlank()) {
        SessionMemoryStore.SessionInfo existing = sessionStore.getSession(userId, requestedSessionId);
        if (existing == null) {
            sessionStore.createActiveSession(userId, requestedSessionId, title, personaId, sourceApp);
        } else {
            sessionManager.switchSession(userId, requestedSessionId);
        }
        return requestedSessionId;
    }
    SessionMemoryStore.SessionInfo active = sessionStore.getActiveSession(userId);
    if (active != null) {
        sessionManager.switchSession(userId, active.sessionId);
        return active.sessionId;
    }
    return createConversationSession(userId, title, personaId, sourceApp);
}
```

### Task 3A.2：AgentLoopOrchestrator 改为每次执行选择 ChatMemory

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Create: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`

- [ ] **Step 1: 移除构造期固定 chatMemory**

将：

```java
private final ChatMemory chatMemory;
```

改为：

```java
private final SessionChatMemoryProvider chatMemoryProvider;
```

构造函数中不再调用 `createChatMemory(context)`，而是从 `MemoryOrchestrator` 获取 provider：

```java
this.chatMemoryProvider = memoryOrchestrator != null
        ? memoryOrchestrator.chatMemoryProvider()
        : null;
```

- [ ] **Step 2: 在 execute 开始时解析 sessionId 和 ChatMemory**

```java
String userId = extraContext != null
        ? (String) extraContext.getOrDefault("user_id", "default_user")
        : "default_user";
String sessionId = extraContext != null ? (String) extraContext.get("session_id") : null;
String personaId = extraContext != null
        ? (String) extraContext.getOrDefault("persona_id", config.personaId())
        : config.personaId();

if (memoryOrchestrator != null && config.memoryPolicy() == AgentConfig.MemoryPolicy.PERSISTENT) {
    sessionId = memoryOrchestrator.resolveSessionId(
            userId, sessionId, "新对话", personaId, "agent_loop");
}

ChatMemory chatMemory = resolveChatMemory(userId, sessionId, personaId);
```

新增：

```java
private ChatMemory resolveChatMemory(String userId, String sessionId, String personaId) {
    if (config.memoryPolicy() == AgentConfig.MemoryPolicy.PERSISTENT && chatMemoryProvider != null) {
        return chatMemoryProvider.get(userId, sessionId, personaId, config.maxMemoryMessages());
    }
    return createEphemeralChatMemory();
}

private ChatMemory createEphemeralChatMemory() {
    int maxMessages = config.memoryPolicy() == AgentConfig.MemoryPolicy.NONE
            ? 2 : config.maxMemoryMessages();
    return MessageWindowChatMemory.builder()
            .maxMessages(maxMessages)
            .build();
}
```

- [ ] **Step 3: injectSystemPrompt 改为接收 ChatMemory**

```java
private void injectSystemPrompt(String userId, ChatMemory chatMemory) {
```

调用点改为：

```java
injectSystemPrompt(userId, chatMemory);
```

- [ ] **Step 4: cleanMemory 改为支持指定会话**

```java
public void cleanMemory(String userId, String sessionId, String personaId) {
    if (chatMemoryProvider != null) {
        chatMemoryProvider.clear(userId, sessionId, personaId);
    }
}

public void cleanMemory() {
    cleanMemory("default_user", null, config.personaId());
}
```

### Task 3A.3：会话隔离测试

**Files:**

- Create: `app/src/test/java/com/hirain/aiagent/core/AgentLoopOrchestratorSessionMemoryTest.java`

- [ ] **Step 1: 单测验证同一用户 A/B 会话历史隔离**

测试目标：

```java
@Test
public void execute_usesDifferentShortTermMemoryForDifferentSessions() {
    // session-A 写入唯一事实 "代号是 alpha"
    // session-B 提问 "我的代号是什么"
    // fake model 收到的 messages 中不应包含 alpha
    // 切回 session-A 后，fake model 收到的 messages 应包含 alpha
}
```

实现方式：

- 使用 fake `ModelCaller` 捕获每次 `ChatRequest.messages()`。
- 使用 fake 或临时 `SessionMemoryStore` 对应的 `SessionChatMemoryProvider`。
- 不调用真实 LLM。

- [ ] **Step 2: 手动验收补充真实链路**

在 `docs/check_accept/conversation-request-protocol-manual-checklist.md` 中增加：

```markdown
## 7. 真实上下文隔离
- [ ] 创建 session-A，说“我的临时代号是 alpha-731”。
- [ ] 创建并切换到 session-B，问“我的临时代号是什么”。AI 不应基于 session-A 短期历史答出 alpha-731。
- [ ] 切回 session-A，问“我的临时代号是什么”。AI 应能延续 session-A 上下文。
- [ ] 删除 session-A 后重新查询列表，session-A 不再出现；再次使用 session-A 时不应恢复旧短期历史。
```

### Task 3A.4：Phase 3A 测试

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.core.AgentLoopOrchestratorSessionMemoryTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## 8. Phase 4：请求取消能力

**目标：** 提供 `cancelAgentRequest(requestId, reason)` AIDL，支持 TEXT 请求协作式取消、timeout 移除、late result 抑制。

### Task 4.1：新增 ActiveRequestRegistry

**Files:**

- Create: `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequest.java`
- Create: `app/src/main/java/com/hirain/aiagent/runtime/ActiveRequestRegistry.java`
- Create: `app/src/test/java/com/hirain/aiagent/runtime/ActiveRequestRegistryTest.java`

- [ ] **Step 1: `ActiveRequest`**

```java
public final class ActiveRequest {
    private final String requestId;
    private final String sessionId;
    private final String userId;
    private final String personaId;
    private final String clientMessageId;
    private final long startedAtMs;
    public enum TerminalState { RUNNING, COMPLETED, CANCELLED, TIMEOUT, FAILED }

    private final AtomicReference<TerminalState> state =
            new AtomicReference<>(TerminalState.RUNNING);
    private volatile String cancelReason;

    public ActiveRequest(String requestId, String sessionId, String userId,
                         String personaId, String clientMessageId, long startedAtMs) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.personaId = personaId;
        this.clientMessageId = clientMessageId;
        this.startedAtMs = startedAtMs;
    }

    public boolean tryComplete(TerminalState terminalState, String reason) {
        boolean changed = state.compareAndSet(TerminalState.RUNNING, terminalState);
        if (changed && terminalState == TerminalState.CANCELLED) {
            cancelReason = reason;
        }
        return changed;
    }

    public boolean isCancelled() { return state.get() == TerminalState.CANCELLED; }
    public TerminalState state() { return state.get(); }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String personaId() { return personaId; }
    public String clientMessageId() { return clientMessageId; }
    public String cancelReason() { return cancelReason; }
}
```

- [ ] **Step 2: `ActiveRequestRegistry`**

```java
public final class ActiveRequestRegistry {
    private final ConcurrentHashMap<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> finishedRequests = new ConcurrentHashMap<>();
    private static final long FINISHED_CACHE_TTL_MS = 60_000L;

    public ActiveRequest register(RequestSession session) {
        ActiveRequest active = new ActiveRequest(
                session.requestId(),
                session.sessionId(),
                session.userId(),
                session.personaId(),
                session.clientMessageId(),
                session.startedAtMs());
        finishedRequests.remove(session.requestId());
        activeRequests.put(session.requestId(), active);
        return active;
    }

    public CancelRequestResult cancel(String requestId, String reason, long timestamp) {
        if (requestId == null || requestId.isEmpty()) {
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        ActiveRequest active = activeRequests.get(requestId);
        if (active == null) {
            Long finishedAt = finishedRequests.get(requestId);
            if (finishedAt != null && timestamp - finishedAt <= FINISHED_CACHE_TTL_MS) {
                return CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
            }
            return CancelRequestResult.notFound(requestId, reason, timestamp);
        }
        return active.tryComplete(ActiveRequest.TerminalState.CANCELLED, reason)
                ? CancelRequestResult.accepted(requestId, reason, timestamp)
                : CancelRequestResult.alreadyFinished(requestId, reason, timestamp);
    }

    public boolean tryComplete(String requestId, ActiveRequest.TerminalState terminalState) {
        ActiveRequest active = activeRequests.get(requestId);
        if (active == null) {
            return false;
        }
        return active.tryComplete(terminalState, null);
    }

    public ActiveRequest remove(String requestId) {
        if (requestId == null) {
            return null;
        }
        ActiveRequest removed = activeRequests.remove(requestId);
        if (removed != null) {
            finishedRequests.put(requestId, System.currentTimeMillis());
        }
        return removed;
    }

    public ActiveRequest get(String requestId) {
        return activeRequests.get(requestId);
    }

    public void finish(String requestId) {
        remove(requestId);
    }

    public void cleanupFinished(long now) {
        finishedRequests.entrySet().removeIf(entry -> now - entry.getValue() > FINISHED_CACHE_TTL_MS);
    }
}
```

- [ ] **Step 3: 单元测试**

测试类中先增加辅助方法：

```java
private static RequestSession newTestSession(String requestId) {
    AgentRequest request = new AgentRequest();
    request.setRequestId(requestId);
    request.setSessionId("session-1");
    request.setUserId("user-1");
    request.setPersonaId("chat");
    request.setText("你好");
    request.setInputType("TEXT");
    return new RequestSession(
            request,
            requestId,
            "session-1",
            "user-1",
            "test",
            "TEXT",
            "chat",
            "client-1",
            "你好",
            1000L,
            null,
            IntentResult.unknown("你好", "TEXT", "test_default"),
            ToolGroupSelectionResult.fallback("test_default"),
            new HashMap<>());
}
```

```java
@Test
public void cancel_returnsAcceptedAndMarksRequestCancelled() {
    ActiveRequestRegistry registry = new ActiveRequestRegistry();
    RequestSession session = newTestSession("req-1");
    registry.register(session);

    CancelRequestResult result = registry.cancel("req-1", "user_stop", 1000L);

    assertTrue(result.isSuccess());
    assertEquals(CancelRequestResult.STATUS_ACCEPTED, result.getStatus());
    assertTrue(registry.get("req-1").isCancelled());
}
```

所有响应发送方都必须先抢占终态：

```java
assertTrue(registry.tryComplete("req-1", ActiveRequest.TerminalState.COMPLETED));
assertFalse(registry.tryComplete("req-1", ActiveRequest.TerminalState.TIMEOUT));
```

再增加 race 语义测试：

```java
@Test
public void cancel_returnsAlreadyFinishedForRecentlyFinishedRequest() {
    ActiveRequestRegistry registry = new ActiveRequestRegistry();
    RequestSession session = newTestSession("req-1");
    registry.register(session);
    registry.finish("req-1");

    CancelRequestResult result = registry.cancel("req-1", "user_stop", System.currentTimeMillis());

    assertFalse(result.isSuccess());
    assertEquals(CancelRequestResult.STATUS_ALREADY_FINISHED, result.getStatus());
}
```

### Task 4.2：RuntimeResult 支持 CANCELLED

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`
- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RuntimeResponseMapperTest.java`

- [ ] **Step 1: RuntimeResult 增加工厂**

```java
public static RuntimeResult cancelled(String requestId, String sessionId,
                                      String userId, String personaId,
                                      String clientMessageId,
                                      String reason, long timestampMs) {
    return new RuntimeResult(requestId, sessionId, userId, personaId, clientMessageId,
            false, null,
            "CANCELLED", reason != null ? reason : "请求已取消", timestampMs, 0, 0);
}
```

- [ ] **Step 2: AgentRuntime 增加 cancelledResult**

```java
public RuntimeResult cancelledResult(RequestSession session, String reason) {
    return RuntimeResult.cancelled(
            session.requestId(),
            session.sessionId(),
            session.userId(),
            session.personaId(),
            session.clientMessageId(),
            reason,
            timeProvider.nowMillis());
}
```

- [ ] **Step 3: Mapper 映射取消**

```java
} else if ("CANCELLED".equals(result.errorType())) {
    response.setSuccess(false);
    response.setText("系统: 请求已取消");
    response.setErrorType("CANCELLED");
    response.setStatus("CANCELLED");
    response.setErrorDetail(result.errorDetail());
}
```

### Task 4.3：AIAgentService 接入 active request

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 新增字段**

```kotlin
private val activeRequestRegistry = ActiveRequestRegistry()
private val activeTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
```

- [ ] **Step 2: Binder 实现 cancel**

```kotlin
override fun cancelAgentRequest(requestId: String?, reason: String?): CancelRequestResult {
    val cancelReason = reason ?: "cancelled_by_client"
    val result = activeRequestRegistry.cancel(requestId, cancelReason, System.currentTimeMillis())
    if (result.success) {
        requestId?.let { id ->
            activeTimeouts.remove(id)?.let { timeoutRunnable ->
                mainHandler.removeCallbacks(timeoutRunnable)
            }
            activeRequestRegistry.get(id)?.let { active ->
                val cancelled = RuntimeResult.cancelled(
                    active.requestId(),
                    active.sessionId(),
                    active.userId(),
                    active.personaId(),
                    active.clientMessageId(),
                    cancelReason,
                    System.currentTimeMillis()
                )
                notifyAIAgentListeners(runtimeResponseMapper.toAgentResponse(cancelled))
                activeRequestRegistry.finish(id)
            }
        }
        stopTTS()
    }
    return result
}
```

- [ ] **Step 3: TEXT 请求注册 active request**

在 `runtimeSession = agentRuntime.startSession(...)` 后：

```kotlin
val activeRequest = activeRequestRegistry.register(runtimeSession)
val timeoutRunnable = Runnable {
    val won = activeRequestRegistry.tryComplete(
        runtimeSession.requestId(),
        ActiveRequest.TerminalState.TIMEOUT
    )
    if (!won) return@Runnable
    val timeoutResponse = runtimeResponseMapper.toAgentResponse(agentRuntime.timeoutResult(runtimeSession))
    notifyAIAgentListeners(timeoutResponse)
    activeTimeouts.remove(runtimeSession.requestId())
    activeRequestRegistry.finish(runtimeSession.requestId())
}
activeTimeouts[runtimeSession.requestId()] = timeoutRunnable
```

- [ ] **Step 4: worker 执行前检查取消**

```kotlin
if (activeRequest.isCancelled) {
    // cancelAgentRequest 已经抢占 CANCELLED 并发送响应；worker 只负责退出。
    activeTimeouts.remove(runtimeSession.requestId())?.let { mainHandler.removeCallbacks(it) }
    activeRequestRegistry.finish(runtimeSession.requestId())
    return@post
}
```

- [ ] **Step 5: worker 执行后按终态发送或抑制 late result**

```kotlin
try {
    val result = agentRuntime.execute(runtimeSession)
    if (activeRequestRegistry.tryComplete(runtimeSession.requestId(), ActiveRequest.TerminalState.COMPLETED)) {
        activeTimeouts.remove(runtimeSession.requestId())?.let { mainHandler.removeCallbacks(it) }
        val response = runtimeResponseMapper.toAgentResponse(result)
        notifyAIAgentListeners(response)
        activeRequestRegistry.finish(runtimeSession.requestId())
    }
} catch (e: Exception) {
    if (activeRequestRegistry.tryComplete(runtimeSession.requestId(), ActiveRequest.TerminalState.FAILED)) {
        activeTimeouts.remove(runtimeSession.requestId())?.let { mainHandler.removeCallbacks(it) }
        val result = agentRuntime.errorResult(runtimeSession, e)
        val response = runtimeResponseMapper.toAgentResponse(result)
        notifyAIAgentListeners(response)
        activeRequestRegistry.finish(runtimeSession.requestId())
    }
}
```

如果请求已被 CANCELLED 或 TIMEOUT 抢占，worker 的 success/error 分支不能再发送 listener 响应、不能再次关闭 Trace。

- [ ] **Step 6: 原 timeout/dispatchAndClose 路径改造**

所有 timeout runnable、worker success、worker error、cancel AIDL 都必须通过 `activeRequestRegistry.tryComplete(...)` 或 `cancel(...)` 抢占终态。只有抢占成功的一方允许：

```kotlin
activeTimeouts.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
notifyAIAgentListeners(response)
activeRequestRegistry.finish(requestId)
```

不允许保留绕过 `ActiveRequestRegistry` 的旧 `dispatchAndClose` 超时路径，否则仍可能出现 TIMEOUT/SUCCESS 双响应。

实现时必须确保 timeout runnable 在 finish 时被移除，避免取消后又发 TIMEOUT。

### Task 4.4：Phase 4 测试

- [ ] **Step 1: 运行取消相关单测**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.runtime.ActiveRequestRegistryTest" --tests "com.hirain.aiagent.runtime.RuntimeResponseMapperTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: 手动模拟**

外部 App 或测试客户端调用顺序：

1. `processAgentRequest(requestId="req-cancel-1", text="给我讲一个很长的故事", inputType="TEXT")`
2. 立即调用 `cancelAgentRequest("req-cancel-1", "user_stop")`
3. 期望同步返回 `status=ACCEPTED`
4. 期望 listener 最多收到一条 `errorType=CANCELLED` 响应
5. 不应再收到同 requestId 的成功响应

---

## 9. Phase 5：响应映射、日志与 Trace 完整性

**目标：** 让外部 App 能稳定根据 `requestId/sessionId/userId/personaId/clientMessageId/status` 做 UI 对账。

### Task 5.1：RuntimeResult/Response 补齐元信息

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResult.java`
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RuntimeResponseMapper.java`

- [ ] **Step 1: RuntimeResult 增加 userId/personaId/clientMessageId**

直接把三项加入 `RuntimeResult` 构造函数和所有工厂方法，不使用二次包装方法：

```java
private final String userId;
private final String personaId;
private final String clientMessageId;

public String userId() { return userId; }
public String personaId() { return personaId; }
public String clientMessageId() { return clientMessageId; }
```

- [ ] **Step 2: AgentRuntime 创建结果时写入 RequestSession 元信息**

`RuntimeResult` 的 6 个工厂方法必须全部增加 `userId/personaId/clientMessageId` 参数，没有例外：

```java
success(requestId, sessionId, userId, personaId, clientMessageId, output, timestampMs, iterationsUsed, durationMs)
failure(requestId, sessionId, userId, personaId, clientMessageId, errorType, errorDetail, timestampMs)
timeout(requestId, sessionId, userId, personaId, clientMessageId, timestampMs)
cancelled(requestId, sessionId, userId, personaId, clientMessageId, reason, timestampMs)
fromAgentResult(requestId, sessionId, userId, personaId, clientMessageId, agentResult, timestampMs)
fromException(requestId, sessionId, userId, personaId, clientMessageId, exception, timestampMs)
```

`execute` 成功/失败、`timeoutResult`、`errorResult`、`cancelledResult` 都必须把 `session.userId()`、`session.personaId()`、`session.clientMessageId()` 传给 `RuntimeResult`：

```java
return RuntimeResult.fromAgentResult(
        session.requestId(),
        session.sessionId(),
        session.userId(),
        session.personaId(),
        session.clientMessageId(),
        result,
        timeProvider.nowMillis());
```

- [ ] **Step 3: Mapper 写入响应**

```java
response.setUserId(result.userId());
response.setPersonaId(result.personaId());
response.setClientMessageId(result.clientMessageId());
response.setErrorDetail(result.errorDetail());
response.setStatus(result.success() ? "SUCCESS" : result.errorType());
```

### Task 5.2：日志与 Trace 属性

**Files:**

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Modify: `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`

- [ ] **Step 1: 明确 Trace key 语义**

`TraceManager.startAgentRequest()` 已经在 root span 写入以下字段：

```text
request.id
session.id
user.id
agent.persona
source.app
input.type
```

因此 Runtime 不再重复写 `agent.request.id` / `agent.session.id` / `agent.user.id`。本阶段只新增外部 App 对账所需的客户端消息 ID：

```java
public static final String CLIENT_MESSAGE_ID = "client_message.id";
```

- [ ] **Step 2: Runtime Trace 只补充 clientMessageId**

```java
private void writeRequestMetaToTrace(TraceContext traceContext, RequestSession session) {
    if (traceContext == null || traceContext.session() == null || session == null) return;
    if (session.clientMessageId() != null) {
        traceContext.session().setAttribute(
                TraceAttributeKeys.CLIENT_MESSAGE_ID,
                session.clientMessageId());
    }
}
```

`AgentRuntime.startSession()` 必须在 `sessionFactory.create(...)` 返回后立即调用：

```java
RequestSession session = sessionFactory.create(request, traceContext, intentResult, toolGroupSelectionResult);
writeRequestMetaToTrace(traceContext, session);
return session;
```

新增或更新单测，使用 fake `TraceSession` 验证当 `clientMessageId=client-1` 时，`TraceAttributeKeys.CLIENT_MESSAGE_ID` 被写入；同时确认没有新增 `agent.request.id/agent.session.id/agent.user.id` 等重复 key。

- [ ] **Step 3: Service 日志**

TEXT 请求开始日志包含：

```text
requestId=<requestId>, sessionId=<sessionId>, userId=<userId>, personaId=<personaId>, clientMessageId=<clientMessageId>
```

取消日志包含：

```text
cancel requestId=<requestId>, status=<status>, reason=<reason>
```

### Task 5.3：Phase 5 测试

- [ ] **Step 1: Runtime mapper 单测覆盖所有状态**

测试用例名称：

```java
toAgentResponse_mapsSuccessMeta()
toAgentResponse_mapsCancelledStatus()
toAgentResponse_mapsTimeoutStatus()
toAgentResponse_mapsExceptionErrorDetail()
```

- [ ] **Step 2: 运行全量本地单测**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected:

```text
BUILD SUCCESSFUL
```

---

## 10. Phase 6：客户端验收与文档

**目标：** 输出可被外部对话 App 使用的手动测试清单，并验证主流程未被破坏。

### Task 6.1：新增手动验收清单

**Files:**

- Create: `docs/check_accept/conversation-request-protocol-manual-checklist.md`

- [ ] **Step 1: 清单必须覆盖**

```markdown
# Conversation Request Protocol Manual Checklist

## 1. TEXT 旧协议兼容
- [ ] 不传 userId/personaId/clientMessageId，发送 TEXT 请求，仍然能收到正常回复。
- [ ] 响应中 requestId/sessionId 与旧逻辑一致。

## 2. 用户切换
- [ ] userId=user_A 新建对话并发送消息。
- [ ] userId=user_B 新建对话并发送消息。
- [ ] listConversations(user_A) 不返回 user_B 的会话。
- [ ] 切回 user_A 的 sessionId 后继续对话，记忆上下文不串到 user_B。

## 3. 对话管理
- [ ] createConversation 返回 success=true 和非空 sessionId。
- [ ] listConversations 返回刚创建的 sessionId。
- [ ] switchConversation 到历史 session 后，下一条 TEXT 请求使用该 sessionId。
- [ ] deleteConversation 后，listConversations 不再返回该 sessionId。

## 4. 取消请求
- [ ] 发送长 TEXT 请求后立即 cancelAgentRequest，返回 ACCEPTED。
- [ ] listener 收到 CANCELLED 响应或不再收到成功 late result。
- [ ] 对不存在 requestId 调 cancel，返回 NOT_FOUND。

## 5. Persona 字段
- [ ] personaId=chat 正常执行。
- [ ] personaId=friendly 正常执行，并从回复风格或 Trace 中能看出使用 friendly persona。
- [ ] personaId=concise 正常执行，并从回复风格或 Trace 中能看出使用 concise persona。
- [ ] personaId=unknown 自动 fallback 到 chat，日志可见 fallback。

## 6. 原有边界
- [ ] IMAGE 请求仍走原逻辑。
- [ ] CONTROL 请求仍走原逻辑。
- [ ] VOICE 的 ASR/TTS 包装仍由 Service 处理。

## 7. 真实上下文隔离
- [ ] 创建 session-A，说“我的临时代号是 alpha-731”。
- [ ] 创建并切换到 session-B，问“我的临时代号是什么”。AI 不应基于 session-A 短期历史答出 alpha-731。
- [ ] 切回 session-A，问“我的临时代号是什么”。AI 应能延续 session-A 上下文。
- [ ] 删除 session-A 后重新查询列表，session-A 不再出现；再次使用 session-A 时不应恢复旧短期历史。
```

### Task 6.2：最终验证命令

- [ ] **Step 1: 编译 Debug**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: 全量本地单测**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: 如连接设备，安装并跑真实对话**

Run:

```powershell
.\gradlew.bat :app:installDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

然后使用外部测试 App 逐项执行 `docs/check_accept/conversation-request-protocol-manual-checklist.md`。

---

## 11. 阶段执行顺序

推荐严格按阶段执行，每个阶段结束后测试通过再进入下一阶段：

1. Phase 1：协议扩展
   - 只改 Parcelable/AIDL。
   - 测试重点：能编译，旧 Runtime 测试不破。
2. Phase 2：会话管理
   - 复用 SessionMemoryStore，不接 Runtime。
   - 测试重点：create/list/switch/delete。
3. Phase 3：用户与 Persona
   - 修正 `userId/sessionId` 混用。
   - 测试重点：`AgentRequest -> RequestSession -> Trace/context` 字段闭环，`chat/friendly/concise` 可真实切换。
4. Phase 3A：真实短期 ChatMemory 会话隔离
   - 将 `AgentLoopOrchestrator` 从固定 `ChatMemory` 改为按请求选择 `ChatMemory`。
   - 测试重点：同用户不同 session 的短期历史隔离，删除 session 后短期历史不可恢复。
5. Phase 4：取消请求
   - 引入 active request registry。
   - 测试重点：取消状态、late result 抑制。
6. Phase 5：响应、日志、Trace
   - 补齐外部 App 对账字段。
   - 测试重点：响应状态一致。
7. Phase 6：手动验收
   - 真实 TEXT 对话、用户切换、对话切换、取消请求。

每个阶段都可以单独提交，建议提交信息：

```text
feat: extend agent request protocol for conversation metadata
feat: expose conversation management over aidl
feat: propagate user and persona through runtime
feat: add cooperative request cancellation
test: add manual checklist for conversation protocol
```

---

## 12. 风险与注意事项

- AIDL 返回 `List<ConversationInfo>` 在部分 Android/AIDL 版本上容易遇到泛型兼容问题，因此计划使用 `ConversationListResponse` 包裹 `ArrayList<ConversationInfo>`。
- Parcelable 字段在同版本 SDK/Service 内必须保持末尾追加，不要插入到现有字段中间；当前无 parcel version/size 边界，因此不承诺新旧二进制跨版本混跑兼容。
- `SessionManager` 当前持有单个 `currentSessionId`，但 `MemoryOrchestrator` 又维护多用户 `UserMemoryContext`。Phase 2 必须把 active session 状态改为按 `userId` 隔离，这是用户切换能力的前置条件。
- 取消请求不能假装硬中断模型调用。验收标准应是“不再把 late result 分发给外部 App”，而不是“HTTP 请求立刻停止”。
- `VOICE` 当前没有完全迁移到 Runtime。用户已明确过 demo 阶段可只测试 TEXT，因此本阶段不应顺手扩大 VOICE 迁移范围。
- `personaId=scene/vision_qa` 不应直接开放给普通 TEXT 对话；这两个配置有专属前处理和输入语义，直接暴露会造成行为混乱。

---

## 13. 实施前确认项

如果实施时需要用户确认，优先问以下问题：

1. 外部 App 是否需要“删除对话但保留消息归档”？本计划默认彻底删除 session 元数据和消息。
2. 取消请求后是否必须通过 listener 回传一条 `CANCELLED` 响应？本计划默认回传，方便 UI 收敛状态。
3. 第一版 TEXT persona 默认开放 `chat/friendly/concise` 三个内置值；其他值 fallback 到 chat 并记录日志。

---

## 14. 自检结果

- 覆盖用户要求：
  - 新增会话管理 AIDL：Phase 1 + Phase 2。
  - 新增取消请求 AIDL：Phase 1 + Phase 4。
  - 扩展主请求协议：Phase 1 + Phase 3。
  - 用户切换：Phase 2 + Phase 3。
  - AI 性格切换：Phase 3，第一版落地 `chat/friendly/concise` 三个内置 TEXT persona；更复杂的动态人格配置留后续阶段。
  - 分阶段测试：Phase 1、Phase 2、Phase 3、Phase 3A、Phase 4、Phase 5、Phase 6 每阶段均有测试门槛。
  - 工作边界：第 2 节已明确。
- 未使用空洞占位描述，所有阶段都给出明确文件、步骤和验证命令。
- 类型一致性：
  - `ConversationRequest/ConversationInfo/ConversationListResponse/ConversationOperationResult/CancelRequestResult` 均在 AIDL 和 Java Parcelable 中成对出现。
  - `userId/personaId/clientMessageId` 在 `AgentRequest -> RequestSession -> RuntimeResult -> AgentResponse` 中闭环。
