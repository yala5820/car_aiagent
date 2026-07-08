# Context 模块手动验收清单

> 在已连接 Android 设备/模拟器上安装 `app-debug.apk`，使用外部对话 App 逐项验证。

---

## 1. TEXT 普通聊天

### 1.1 基本 TEXT 请求正常响应

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="你好，介绍一下你自己"
)
```

**预期结果：**
- `AgentResponse.success=true`
- 回复文本非空，内容为车载 AI 助手的自我介绍
- 整个过程无崩溃或超时

---

## 2. Persona 隔离

### 2.1 chat persona 正常执行

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    personaId="chat",
    text="介绍你自己"
)
```

**预期结果：**
- 回复为默认 chat 风格，语气中性、简洁
- Trace 中 `agent.context.selected_tool_count` 值根据意图变化

### 2.2 friendly persona 风格区别明显

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    personaId="friendly",
    text="介绍你自己"
)
```

**预期结果：**
- 回复风格明显比 chat 更热情、口语化，可能带有感叹号或更亲切的语气
- **关键：** 与步骤 2.1 的回复应当有明显风格差异
- Trace 可见 `agent.context.providers` 包含 PersonaContextProvider

### 2.3 concise persona 风格简洁

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    personaId="concise",
    text="介绍你自己"
)
```

**预期结果：**
- 回复明显比 chat 简短，可能只有一两句话
- **关键：** 与步骤 2.1 相比，回复长度应大幅缩短

### 2.4 ChatMemory 互相隔离

**步骤：**
1. 用 `personaId="chat"` 发消息："我喜欢听古典音乐"
2. 用 `personaId="friendly"` 发消息："你喜欢什么音乐"
3. 用 `personaId="chat"` 发消息："我刚才说了什么"

**预期结果：**
- 第 3 步的回复应当记得第 1 步中说的"古典音乐"
- friendly persona 在第 2 步的回复可能不包含"古典音乐"信息（因为 chat 和 friendly 的记忆分开存储）

---

## 3. 会话切换与 ContextFrame.sessionId

### 3.1 创建切换后 sessionId 变化

**步骤：**
1. `createConversation(userId="user-test")` → 记录返回的 session-A
2. 用 session-A 发 TEXT："天气怎么样"
3. `createConversation(userId="user-test")` → 记录返回的 session-B
4. `switchConversation("user-test", session-B)` → 切换到 B
5. 用 session-B 发 TEXT："今天有什么新闻"

**预期结果：**
- 步骤 2 和 5 的请求都正常回复
- 如果能在 Trace 中观测到 `context_frame`，步骤 2 的 `sessionId=session-A`，步骤 5 的 `sessionId=session-B`

### 3.2 切回历史会话后 session 恢复

**步骤：**
1. 接上一步，`switchConversation("user-test", session-A)` 切回 A
2. 发 TEXT："我刚才问了什么"

**预期结果：**
- 回复应当记得"天气怎么样"的内容，而不是 session-B 的对话

---

## 4. 工具选择

### 4.1 空调请求只选中空调和基础状态工具

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="把空调打开，温度调到26度"
)
```

**预期结果：**
- 模型正常执行空调相关工具（如 `set_ac_status`、`set_ac_drive_temp`）
- Trace 中 `agent.context.selected_tool_names` 包含空调工具名（如 `set_ac_status`）
- Trace 中 `agent.context.selected_tool_names` **不包含**车窗工具（如 `set_fl_window_status`、`set_fr_window_status`）
- Trace 中 `agent.context.selected_tool_count` 应小于 47

### 4.2 车窗请求只选中车窗和基础状态工具

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="打开主驾车窗"
)
```

**预期结果：**
- 模型正常执行车窗工具（如 `set_fl_window_status`）
- Trace 中 `agent.context.selected_tool_names` 包含车窗工具
- Trace 中 `agent.context.selected_tool_names` **不包含**空调工具（如 `set_ac_status`）
- 确认 `agent.context.selected_group_ids` 包含 `WINDOW_GROUP`

### 4.3 座椅请求只选中座椅和基础状态工具

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="打开座椅加热"
)
```

**预期结果：**
- 模型正常执行座椅工具（如 `set_seat_fl_heat`）
- Trace 中 `agent.context.selected_tool_names` 包含座椅工具
- Trace 中 `agent.context.selected_tool_names` **不包含**空调或车窗工具

### 4.4 普通聊天不是全量工具

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="你好，今天心情不错，给我讲个笑话吧"
)
```

**预期结果：**
- 模型正常回复笑话，不会调用任何车控工具
- Trace 中 `agent.context.selected_tool_count` < 47
- Trace 中 `agent.context.selected_group_ids` 应为 `CHAT_ONLY_GROUP` 或较小集合

---

## 5. Context 注入验证

### 5.1 模型消息中包含 Context 文本

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="打开空调"
)
```

如果能在抓包或日志中看到发给 LLM 的 ChatRequest 消息：

**预期结果：**
- 消息列表中包含一个由 ContextExtraPreProcessor 注入的 UserMessage
- 该消息内容包含以下三段文本：
  - `【运行时上下文】`（附有 requestId、userId 等信息）
  - `【意图上下文】`（附有 intentTag、confidence 等信息）
  - `【工具组上下文】`（附有 selectedGroupIds、selectedToolNames 等信息）

### 5.2 模型中不包含重复内容

检查上一步的 ChatRequest 消息：

**预期结果：**
- 消息中不包含 `当前时间：` 字样（因为 TimeContextProvider renderable=false）
- 消息中不包含 `【长期记忆】` 字样（因为 MemoryContextProvider renderable=false）
- 消息中不包含 `车辆状态：` 字样（因为 VehicleStateContextProvider renderable=false）
- 消息中不包含 system prompt 的模板内容（如 `你是` 开头，因为 PromptContextProvider renderable=false，system prompt 由 AgentLoopOrchestrator.injectSystemPrompt 单独注入）

以上四条表示 Context 模块没有与已有的 PreProcessor 重复注入。

### 5.3 调用方 extra_context 不受影响

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="你好",
    extraContext=mapOf("extra_context" to "自定义业务值")
)
```

**预期结果：**
- LLM 收到的消息中不包含"自定义业务值"（因为 Context 模块不覆盖调用方传入的 extra_context）
- 在 Trace 或调试日志中可见 `caller_extra_context` 保留了传入的值
- `context_rendered_extra` 和 `extra_context` 互不干扰

---

## 6. 取消请求

### 6.1 Context 构建后、AgentLoop 前取消

**步骤：**
1. 发送一个长 TEXT 请求（如"给我讲一个很长的故事，讲够五千字"）
2. 在发送后立即调用 `cancelAgentRequest(requestId, "user_stop")`

**预期结果：**
- `cancelAgentRequest` 返回 `success=true, status=ACCEPTED`
- Listener 收到 `AgentResponse(errorType="CANCELLED")`
- **关键：** 不应收到该 requestId 的成功回复（说明 cancel 成功阻止了 AgentLoop 启动）
- 日志中应可见 `cancelled_before_agent_loop`（RuntimeCancelChecker 拦截）

### 6.2 取消发生在 Runtime 返回后

**步骤：**
1. 发送长 TEXT 请求，在请求处理中快速连续取消两次

**预期结果：**
- 第一次取消：`ACCEPTED`
- 第二次取消：`NOT_FOUND` 或 `ALREADY_FINISHED`
- 不会收到 late success 响应

### 6.3 Timeout 验证

**发送内容：**

```
AgentRequest(
    inputType="TEXT",
    text="给我讲一个非常长的故事，越长越好"
)
```

等待 timeout 触发（当前配置 30 秒）：

**预期结果：**
- 只收到一次 `AgentResponse(errorType="TIMEOUT")`
- 不会先收到 timeout 再收到 success

---

## 7. 非回归验证

### 7.1 IMAGE 请求不受影响

**发送内容：**

```
AgentRequest(
    inputType="IMAGE",
    text="前面有车吗",
    imagePath="..."
)
```

**预期结果：**
- 正常走 VLM 处理链路，行为与改动前一致
- 不走 Context 模块的 TEXT 处理流程

### 7.2 VOICE 请求不受影响

**发送内容：**

```
AgentRequest(
    inputType="VOICE",
    text="打开空调"
)
```

**预期结果：**
- 正常走语音处理链路
- ContextExtraPreProcessor 不会注入额外消息（因 context map 中没有 `context_rendered_extra` 字段）

### 7.3 CONTROL 请求不受影响

**发送内容：**

```
AgentRequest(
    inputType="CONTROL",
    text="StartListen"
)
```

**预期结果：**
- 正常走 CONTROL 处理链路，不受 Context 模块影响

---

## Trace 期望字段汇总

在 Phoenix/Observability 面板中查找 root span `agent.request`，应看到以下属性：

| 属性 | 类型 | 期望值 |
|------|------|--------|
| `agent.context.enabled` | boolean | `true` |
| `agent.context.mode` | string | `HYBRID_EXTRA_CONTEXT` |
| `agent.context.provider_count` | int | `9` |
| `agent.context.providers` | string | 逗号分隔的 9 个 Provider 名（以 RuntimeContextProvider 开头，以 PromptContextProvider 结尾） |
| `agent.context.selected_tool_count` | int | 根据请求不同：空调=空调组工具数，聊天=0 |
| `agent.context.selected_tool_names` | string | 逗号分隔的选中工具名 |
| `agent.context.section_count` | int | 正常情况 >= 5（非 renderable 的 section 也被计数） |
| `agent.context.token_estimate` | int | >= 0，约 renderedExtraContext 字符数的一半 |
| `agent.context.fallback_used` | boolean | `false`（正常情况下） |
| `agent.context.build_ms` | int | >= 0，通常 < 50ms（9 个 Provider 无 IO 调用） |
| `agent.context.error` | string | 正常情况下为空（属性不存在） |

---

## 最终验证结果记录

| 场景 | 通过数/总项数 | 备注 |
|------|-------------|------|
| 1. TEXT 普通聊天 | /1 | |
| 2. Persona 隔离 | /4 | |
| 3. 会话切换 | /2 | |
| 4. 工具选择 | /4 | |
| 5. Context 注入 | /3 | |
| 6. 取消请求 | /3 | |
| 7. 非回归 | /3 | |
| **总计** | **/20** | |
