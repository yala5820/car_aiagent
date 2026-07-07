# AgentRuntime 手动验收清单

## 验收目标

验证 AgentRuntime 引入后，`TEXT` 请求主链路已由 Runtime 接管，同时 `VOICE`、`IMAGE`、`CONTROL` 行为保持迁移前一致。

## 前置条件

- App 已安装到目标车机或 Android 测试设备。
- `local.properties` 中 DashScope / Weather 等本地配置可用。
- Launcher 或 AIDL 调用方可以发送 `AgentRequest`。
- Logcat 可过滤 `TAG`、`TraceSession`、`AIAgentService` 相关日志。
- 如需检查 Phoenix trace，Phoenix 服务处于可访问状态。

## 1. TEXT 正常对话

- [ ] 发送 `inputType=TEXT`，`text=你好，介绍一下你能做什么`。
- [ ] 收到一次且仅一次 `AgentResponse`。
- [ ] `success=true`。
- [ ] `text` 非空，语义与迁移前主对话能力一致。
- [ ] Logcat 可见 `handleTextRequest begin`。
- [ ] Logcat 不应出现 `handleTextRequest service-level failure`。

## 2. TEXT requestId / sessionId 保持

- [ ] 发送 `inputType=TEXT`，携带 `requestId=req-manual-001`，`sessionId=session-manual-001`。
- [ ] 返回的 `AgentResponse.requestId=req-manual-001`。
- [ ] 返回的 `AgentResponse.sessionId=session-manual-001`。
- [ ] 连续发送第二条相同 `sessionId` 的 TEXT 请求。
- [ ] 对话上下文表现与迁移前基本一致。

## 3. TEXT 缺失 sessionId 默认行为

- [ ] 发送 `inputType=TEXT`，不设置 `sessionId`。
- [ ] 请求可以正常返回。
- [ ] 返回 `AgentResponse.sessionId` 不应被 Runtime 伪造为新的业务 sessionId。
- [ ] 对话仍可走现有默认用户逻辑。

## 4. TEXT 工具调用链路

- [ ] 发送明确车控类 TEXT 请求，例如 `帮我打开空调`。
- [ ] 返回语义与迁移前基本一致。
- [ ] 如触发 Tool Calling，工具执行结果与迁移前一致。
- [ ] 不应出现 ToolRegistry / ToolDispatcher 相关异常。
- [ ] 虚拟车辆状态机状态变化与迁移前一致。

## 5. TEXT timeout 行为

- [ ] 使用网络断开、模型不可达或测试环境延迟方式制造 TEXT 超时。
- [ ] 约 15 秒后收到一次 `AgentResponse`。
- [ ] `success=false`。
- [ ] `errorType=TIMEOUT`。
- [ ] `text=系统: 请求超时`。
- [ ] 超时后如果模型稍后返回，不应再次通知 listener。

## 6. TEXT 异常兜底

- [ ] 使用无效模型配置、临时断网或其他安全方式触发 TEXT 请求异常。
- [ ] 收到一次失败响应。
- [ ] `errorType=EXCEPTION` 或原 AgentResult 错误类型。
- [ ] `text` 应包含可理解的失败信息。
- [ ] Service 不应崩溃。

## 7. VOICE 行为不变

- [ ] 通过语音入口发送一句普通对话。
- [ ] ASR/TTS 包装行为与迁移前一致。
- [ ] 返回后可以正常播报 TTS。
- [ ] Logcat 中 `handleVoiceRequest` 仍按原逻辑执行。
- [ ] 不应因为本次 Runtime 改造导致语音请求无响应或重复播报。

## 8. IMAGE 行为不变

- [ ] 发送 `inputType=IMAGE` 且携带有效 `imagePath`。
- [ ] 返回视觉问答结果。
- [ ] `VlManager.frontCameraInteractionPositive(...)` 行为与迁移前一致。
- [ ] 缺失或无效 `imagePath` 时仍返回原有错误语义。

## 9. CONTROL 行为不变

- [ ] 发送 `inputType=CONTROL`，`text=StartListen`。
- [ ] 进入监听状态行为与迁移前一致。
- [ ] 发送 `inputType=CONTROL`，`text=StopListen`。
- [ ] 停止监听行为与迁移前一致。
- [ ] 发送 `inputType=CONTROL`，`text=ClearChatMemory`。
- [ ] 记忆清理行为与迁移前一致。

## 10. Trace 验收

- [ ] TEXT 请求仍创建 root `agent.request` trace。
- [ ] TEXT 请求仍能看到 `response.dispatch`。
- [ ] 正常模型调用仍能看到 `prompt.assembly` 和 `gen_ai.chat`。
- [ ] 工具调用场景仍能看到 `tool.execute`。
- [ ] timeout 场景 root span 应关闭，不应悬挂。
- [ ] Runtime 不应额外创建新的 root trace。

## 11. 回归记录

| 项目 | 结果 | 备注 |
|------|------|------|
| TEXT 正常对话 | 待填写 | |
| TEXT requestId/sessionId | 待填写 | |
| TEXT 缺失 sessionId | 待填写 | |
| TEXT 工具调用 | 待填写 | |
| TEXT timeout | 待填写 | |
| TEXT 异常兜底 | 待填写 | |
| VOICE 不变性 | 待填写 | |
| IMAGE 不变性 | 待填写 | |
| CONTROL 不变性 | 待填写 | |
| Trace 完整性 | 待填写 | |

## 通过标准

- 所有 TEXT 主链路用例通过，且不会重复回调 listener。
- VOICE、IMAGE、CONTROL 与迁移前行为一致。
- timeout 和异常路径都有明确响应，Service 不崩溃。
- TraceSession 仍由 Service 管理，trace span 正常闭合。
