# Conversation Request Protocol Manual Checklist

> 在已连接 android 设备/模拟器上安装 `app-debug.apk`，使用外部对话 App 逐项验证。

---

## 1. TEXT 旧协议兼容

> 验证协议扩展不破坏已有 TEXT 请求。

- [ ] **不传 userId/personaId/clientMessageId，发送 TEXT 请求**\
  操作：构建 `AgentRequest(inputType=TEXT, text="你好")`，不设 userId/personaId/clientMessageId，调用 `processAgentRequest`。\
  预期：收到 `AgentResponse(success=true, text=非空)`。

- [ ] **响应 requestId/sessionId 不为空**\
  操作：同上。\
  预期：`response.requestId` 不为空；`response.sessionId` 与上游行为一致。

---

## 2. 用户切换

> 验证 `userId` 字段正确隔离对话和活跃 session。

- [ ] **userId=user_A 新建对话并发送消息**\
  操作：`createConversation(userId="user_A")` → 记录返回的 sessionId。调用 `processAgentRequest` 发送 "我是张三"。\
  预期：收到正常回复，对话被创建。

- [ ] **userId=user_B 新建对话并发送消息**\
  操作：`createConversation(userId="user_B")` → 记录返回的 sessionId。调用 `processAgentRequest` 发送 "我是李四"。\
  预期：收到正常回复，B 对话创建成功。

- [ ] **listConversations 返回结果互相隔离**\
  操作：`listConversations("user_A")` 和 `listConversations("user_B")`。\
  预期：user_A 的结果中不包含 user_B 的 sessionId，反之亦然。

- [ ] **切换回 user_A 后，下一条 TEXT 请求使用 user_A 的活跃对话**\
  操作：`getActiveConversation("user_A")` → 返回 user_A 第一个创建的 sessionId。发送 TEXT 请求 "我刚才说了什么？"，请求中使用 `userId=user_A`。\
  预期：AI 的回复表明它记得"我是张三"的对话上下文，不混入 user_B 的记忆。

---

## 3. 对话管理

> 验证 create / list / switch / delete 功能。

- [ ] **createConversation 返回 success=true 和非空 sessionId**\
  操作：`createConversation(userId="user-C", personaId="chat", title="测试对话", sourceApp="test")`。\
  预期：`ConversationOperationResult.success=true`，`conversationInfo.sessionId` 非空。

- [ ] **listConversations 返回刚创建的 sessionId**\
  操作：`listConversations("user-C")`。\
  预期：列表中包含上一步创建的 sessionId，且 `title="测试对话"`。

- [ ] **switchConversation 到历史 session 后，下一条 TEXT 请求使用该 sessionId**\
  操作：通过 `switchConversation("user-C", "历史sessionId")` 切换，随后发送 `processAgentRequest(AgentRequest(userId="user-C", text="刚才说了什么？"))`。\
  预期：AI 基于 sessionId 指定的历史上下文回复，而非默认活跃 session。

- [ ] **deleteConversation 后，listConversations 不再返回该 sessionId**\
  操作：`deleteConversation("user-C", sessionId)` → `listConversations("user-C")`。\
  预期：列表中已不再包含被删除的 sessionId。

---

## 4. 取消请求

> 验证 cancelAgentRequest 的终态抢占机制。

- [ ] **发送长 TEXT 请求后立即 cancelAgentRequest，返回 ACCEPTED**\
  操作：`processAgentRequest(requestId="req-cancel-1", text="给我讲一个很长的故事", inputType="TEXT")` → 立即调用 `cancelAgentRequest("req-cancel-1", "user_stop")`。\
  预期：`CancelRequestResult.success=true`，`status=ACCEPTED`。

- [ ] **listener 收到 CANCELLED 响应或不再收到成功 late result**\
  操作：接上一步，监听 `IAIAgentAidlListener.onAIResponse`。\
  预期：最多收到一条 `AgentResponse(errorType="CANCELLED")`，不会后续再收到同 requestId 的成功响应。

- [ ] **对不存在 requestId 调 cancel，返回 NOT_FOUND**\
  操作：`cancelAgentRequest("non-existent-id", "reason")`。\
  预期：`CancelRequestResult.success=false`，`status=NOT_FOUND`。

---

## 5. Persona 字段

> 验证内置 TEXT persona 切换能力。

- [ ] **personaId=chat 正常执行**\
  操作：发 TEXT 请求 `AgentRequest(personaId="chat", text="介绍自己")`。\
  预期：收到回复，风格为默认 chat persona。

- [ ] **personaId=friendly 正常执行，回复风格友好活泼**\
  操作：`AgentRequest(personaId="friendly", text="介绍自己")`。\
  预期：收到回复，与 chat 回复风格明显不同（更热情、口语化）。也可查 Trace span attribute `agent.persona=friendly`。

- [ ] **personaId=concise 正常执行，回复简洁**\
  操作：`AgentRequest(personaId="concise", text="介绍自己")`。\
  预期：回复明显比 chat 简短。Trace span attribute `agent.persona=concise`。

- [ ] **personaId=unknown 自动 fallback 到 chat，日志可见 fallback**\
  操作：`AgentRequest(personaId="warm", text="你好")`。\
  预期：回复风格与 chat 相同。Logcat 过滤 `TAG`，可见 `Unsupported TEXT persona=warm, fallback to chat`。

---

## 6. 原有边界

> 验证未迁移的请求类型不受影响。

- [ ] **IMAGE 请求仍走原逻辑**\
  操作：`processAgentRequest(AgentRequest(inputType="IMAGE", text="前面有车吗", imagePath=...))`。\
  预期：行为与改动前一致。

- [ ] **CONTROL 请求仍走原逻辑**\
  操作：`processAgentRequest(AgentRequest(inputType="CONTROL", text="StartListen"))`。\
  预期：行为与改动前一致。

- [ ] **VOICE 的 ASR/TTS 包装仍由 Service 处理**\
  操作：发送 VOICE 请求。\
  预期：VOICE 文本结果仍被送到 AgentLoopOrchestrator 处理，TTS 播放由 Service 控制。

---

## 7. 真实上下文隔离

> 验证 create/switch/delete 对话不仅改元数据，还真实改变 LLM 可见的短期历史。
> 本场景同时依赖 AgentLoopOrchestrator SessionChatMemoryProvider 和 SessionMemoryStore。

- [ ] **创建 session-A，写入唯一事实**\
  操作：`createConversation` → 记下 session-A。`processAgentRequest(AgentRequest(sessionId=session-A, text="我的临时代号是 alpha-731"))`。\
  预期：AI 正常回复，确认收到临时代号。

- [ ] **创建并切换到 session-B，短期历史不包含 session-A 的事实**\
  操作：`createConversation` → 记下 session-B。`processAgentRequest(AgentRequest(sessionId=session-B, text="我的临时代号是什么"))`。\
  预期：AI 不应答出 alpha-731（因为 session-B 的短期记忆中无该事实）。

- [ ] **切回 session-A，短期记忆仍然保留**\
  操作：`switchConversation` 回到 session-A。`processAgentRequest(AgentRequest(sessionId=session-A, text="我的临时代号是什么"))`。\
  预期：AI 应能答出 alpha-731，证明 session-A 的短期历史完整保留。

- [ ] **删除 session-A 后重新查询列表，session-A 不再出现**\
  操作：`deleteConversation(userId, session-A)` → `listConversations(userId)`。\
  预期：返回列表中无 session-A。且后续使用 session-A 发起请求时不应恢复旧短期历史。

---

## 最终验证结果记录

| 场景 | 通过数/总项数 | 备注 |
|------|-------------|------|
| 1. TEXT 旧协议兼容 | /2 | |
| 2. 用户切换 | /4 | |
| 3. 对话管理 | /4 | |
| 4. 取消请求 | /3 | |
| 5. Persona 字段 | /4 | |
| 6. 原有边界 | /3 | |
| 7. 真实上下文隔离 | /4 | |
| **总计** | **/24** | |
