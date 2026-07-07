# ToolGroup TEXT 对话与 Trace 手动验收清单

## 1. 验收目标

验证 ToolGroup 改造在真实 App / Launcher TEXT 对话链路中的表现：

- TEXT 请求仍由 `AIAgentService -> AgentRuntime -> AgentLoopOrchestrator` 主链路处理。
- `IntentRouter` 与 `ToolGroupSelector` 只生成观测信息，不改变用户可见对话结果。
- Trace root span 中能看到 `agent.intent.*` 与 `agent.tool_group.*` 字段。
- 不限制 LLM 实际可见工具，不改变 ToolRegistry / ToolDispatcher / VehicleStateMachine 行为。
- 能暴露 `UNKNOWN + 弱车载关键词` fallback 是否在真实默认链路中生效。

## 2. 验收前置条件

- AIAgent App 已安装并启动，前台 Service 正常运行。
- Launcher 或测试 App 能通过 AIDL 发送 `AgentRequest(inputType="TEXT")`。
- DashScope API Key、网络、模型访问正常。
- Phoenix / OpenTelemetry Trace 后端可访问，能够查看 `agent.request` root span。
- 虚拟车辆状态机处于可观测状态；如需要，可在测试前记录空调、车窗等初始状态。
- 每个用例记录 `requestId`、`sessionId`、输入文本、用户可见输出、Trace 链接或 traceId。

## 3. 通用通过标准

每条 TEXT 用例均需满足：

- 请求没有崩溃、无超时、无 Binder 异常。
- 用户可见回复合理，且没有出现 ToolGroup / IntentRouter 内部调试文案。
- Trace 中存在 `agent.request` root span。
- Trace 中 `agent.intent.source_input_type=TEXT`。
- Trace 中存在以下 ToolGroup 字段：
  - `agent.tool_group.selected_group_ids`
  - `agent.tool_group.selected_tool_names`
  - `agent.tool_group.selection_reason`
  - `agent.tool_group.confidence`
  - `agent.tool_group.fallback_used`
- 若用例预期不调用工具，则不应出现无关 `tool.execute` 车控调用。
- 若用例预期调用工具，实际工具调用仍应由原 AgentLoop / Tool Calling 决定，不应只因为 ToolGroup 选择而直接执行。

## 4. 用例清单

### TC-TG-TEXT-001 空调控制意图

- 输入：`打开空调`
- 预期用户可见结果：回复应确认或说明空调相关操作结果。
- 预期 Intent Trace：
  - `agent.intent.tag=VEHICLE_AC`
  - `agent.intent.confidence=MEDIUM` 或 `HIGH`
  - `agent.intent.matched_keywords` 包含 `空调`
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=AC_GROUP,BASIC_STATUS_GROUP`
  - `agent.tool_group.selected_tool_names` 包含 `set_ac_status`
  - `agent.tool_group.selection_reason=intent:VEHICLE_AC`
  - `agent.tool_group.fallback_used=false`
- 额外检查：如 LLM 触发工具调用，`tool.execute` 的 tool name 应为现有空调工具之一。

### TC-TG-TEXT-002 车窗控制意图

- 输入：`把车窗降下来`
- 预期用户可见结果：回复应确认或说明车窗相关操作结果。
- 预期 Intent Trace：
  - `agent.intent.tag=VEHICLE_WINDOW`
  - `agent.intent.matched_keywords` 包含 `车窗` 或命中窗口相关正则
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=WINDOW_GROUP,BASIC_STATUS_GROUP`
  - `agent.tool_group.selected_tool_names` 包含 `set_fl_window_status`
  - `agent.tool_group.selection_reason=intent:VEHICLE_WINDOW`
  - `agent.tool_group.fallback_used=false`

### TC-TG-TEXT-003 天气查询意图

- 输入：`今天北京天气怎么样`
- 预期用户可见结果：回复应给出天气信息或说明天气查询结果。
- 预期 Intent Trace：
  - `agent.intent.tag=WEATHER`
  - `agent.intent.matched_keywords` 包含 `天气`
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=WEATHER_GROUP`
  - `agent.tool_group.selected_tool_names=getWeatherForecast`
  - `agent.tool_group.selection_reason=intent:WEATHER`
  - `agent.tool_group.fallback_used=false`
- 额外检查：不应附加 `BASIC_STATUS_GROUP`。

### TC-TG-TEXT-004 纯聊天意图

- 输入：`你好，讲个笑话`
- 预期用户可见结果：正常聊天回复。
- 预期 Intent Trace：
  - `agent.intent.tag=CHAT`
  - `agent.intent.confidence=LOW`
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=CHAT_ONLY_GROUP`
  - `agent.tool_group.selected_tool_names` 为空
  - `agent.tool_group.selection_reason=intent:CHAT`
  - `agent.tool_group.fallback_used=false`
- 额外检查：不应出现车控、天气、视觉类 `tool.execute`。

### TC-TG-TEXT-005 视觉问答意图

- 输入：`看看前方有什么`
- 前置条件：前向摄像头 / VLM 能正常使用；若当前设备不具备视觉能力，本用例标记为环境跳过。
- 预期用户可见结果：回复应尝试回答前方画面相关问题，或给出视觉能力不可用的明确说明。
- 预期 Intent Trace：
  - `agent.intent.tag=VISION_QA`
  - `agent.intent.matched_keywords` 包含 `前方`、`看到`、`看见` 或视觉相关关键词
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=VISION_GROUP`
  - `agent.tool_group.selected_tool_names=front_camera_interaction`
  - `agent.tool_group.selection_reason=intent:VISION_QA`
  - `agent.tool_group.fallback_used=false`
- 额外检查：不应附加 `BASIC_STATUS_GROUP`。

### TC-TG-TEXT-006 弱车载 UNKNOWN fallback

- 输入：`车里有点不舒服`
- 测试目的：验证默认 Runtime 链路中弱车载 fallback 是否真实生效。
- 目标预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=COMMON_VEHICLE_GROUP,BASIC_STATUS_GROUP`
  - `agent.tool_group.selection_reason=fallback:unknown_vehicle_keyword`
  - `agent.tool_group.fallback_used=true`
- 当前风险观察：
  - 如果 Trace 显示 `agent.intent.tag=CHAT` 且 `agent.tool_group.selected_group_ids=CHAT_ONLY_GROUP`，则说明弱车载 fallback 未在默认 `KeywordIntentRouter + DefaultToolGroupSelector` 组合中生效，应记录为失败。
- 用户可见结果要求：无论 fallback 是否生效，都不应崩溃；回复应能继续对话。

### TC-TG-TEXT-007 空文本 / 无效文本降级

- 输入：空字符串或只包含空格。
- 预期用户可见结果：不崩溃，给出可理解的兜底回复。
- 预期 Intent Trace：
  - `agent.intent.tag=UNKNOWN`
  - `agent.intent.debug_reason=empty_text`
- 预期 ToolGroup Trace：
  - `agent.tool_group.selected_group_ids=CHAT_ONLY_GROUP`
  - `agent.tool_group.selection_reason=fallback:unknown_chat`
  - `agent.tool_group.fallback_used=true`

## 5. 非回归检查

- IMAGE 请求仍走原 `handleImageRequest`，本清单不要求迁移。
- VOICE 请求当前不要求经过 IntentRouter / ToolGroup；如真实 VOICE Trace 没有 `agent.tool_group.*`，本阶段不视为失败。
- CONTROL 请求仍走原 `handleControlRequest`。
- `ToolRegistry` / `ToolDispatcher` 中实际可用工具列表不应因为本次测试变化。
- 如果 Phoenix 中出现 `agent.tool_group.selected_tool_names`，它只代表候选工具，不代表 LLM 工具可见性已被限制。

## 6. 记录模板

| 用例 | 输入 | 用户可见结果 | traceId / 链接 | IntentTag | ToolGroupIds | 是否调用工具 | 结论 | 备注 |
|------|------|--------------|----------------|-----------|--------------|--------------|------|------|
| TC-TG-TEXT-001 | 打开空调 |  |  |  |  |  |  |  |
| TC-TG-TEXT-002 | 把车窗降下来 |  |  |  |  |  |  |  |
| TC-TG-TEXT-003 | 今天北京天气怎么样 |  |  |  |  |  |  |  |
| TC-TG-TEXT-004 | 你好，讲个笑话 |  |  |  |  |  |  |  |
| TC-TG-TEXT-005 | 看看前方有什么 |  |  |  |  |  |  |  |
| TC-TG-TEXT-006 | 车里有点不舒服 |  |  |  |  |  |  |  |
| TC-TG-TEXT-007 | 空文本 |  |  |  |  |  |  |  |

## 7. 通过判定

- 必测用例：TC-TG-TEXT-001、002、003、004、006、007。
- 条件用例：TC-TG-TEXT-005，取决于设备视觉能力。
- 所有必测用例均满足通用通过标准。
- TC-TG-TEXT-001/002/003/004 的 Trace 与预期 ToolGroup 一致。
- TC-TG-TEXT-006 如果仍落到 `CHAT_ONLY_GROUP`，则本轮 ToolGroup 手动验收不能判定为完全通过。
- 无用户可见回归：TEXT 对话输出没有明显劣化，IMAGE / VOICE / CONTROL 不出现本阶段引入的异常。
