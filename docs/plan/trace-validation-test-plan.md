# Main Agent Trace 验证方案

日期：2026-07-03

## 1. 目标

本方案用于在真实设备或模拟器上验证主 Agent `TEXT` 请求的 trace 完整性、可调试性和基本性能表现。测试重点覆盖：

- `agent.request` root span 是否完整闭合。
- `prompt.assembly` 是否记录实际送入 LLM 的消息结构和 tool specs。
- `gen_ai.chat` 是否记录模型、token、输出、tool call、HTTP 状态和错误。
- `tool.execute` 是否记录工具名、参数、结果、安全拦截。
- `memory.extract` 是否记录记忆提取 prompt、输出、候选数量。
- `memory.compress` 是否记录压缩决策、压缩 prompt、摘要结果。
- `response.dispatch` 是否记录最终响应，并回写 root summary。
- trace 开启后端到端耗时、span 耗时和 Phoenix 导出延迟是否处于可接受范围。

本方案只验证主 AgentLoop 的 `TEXT` 链路，不覆盖 `IMAGE` / VLM 直接调用、VR/TTS、场景 Agent、Camera 抓拍链路。

## 2. 测试前置条件

### 2.1 环境准备

1. 启动 Phoenix，并确认 OTLP HTTP endpoint 可用：

```text
http://localhost:6006/v1/traces
```

2. 如果使用真机或模拟器，通过 ADB 反向端口转发：

```powershell
adb reverse tcp:6006 tcp:6006
```

3. 启动 AIAgent Service。

4. 通过 Launcher、测试 App 或 AIDL 测试客户端发送 `AgentRequest`。

建议基础请求字段如下：

| 字段 | 建议值 |
|------|--------|
| `inputType` | `TEXT` |
| `sourceApp` | `trace-test-client` |
| `requestId` | 每次请求唯一，例如 `trace-t01-001` |
| `sessionId` | 普通测试每组独立；memory 测试必须复用同一个 session |
| `text` | 使用下方测试 prompt |

### 2.2 Phoenix 通用检查项

每条请求都应至少看到一个 root span：

```text
agent.request
```

root span 通用 attribute：

| Attribute | 预期 |
|-----------|------|
| `agent.persona` | `chat` |
| `request.id` | 与本次 `AgentRequest.requestId` 一致 |
| `session.id` | 与本次 `AgentRequest.sessionId` 一致 |
| `source.app` | `trace-test-client` 或实际调用方 |
| `input.type` | `TEXT` |
| `user.input.length` | 等于或接近 prompt 字符长度 |
| `user.input` | redacted 模式下可见，长文本会截断 |
| `response.success` | 成功 case 为 `true`，timeout/error case 为 `false` |
| `response.text.length` | 最终响应文本长度 |

普通成功请求的基础 span 树通常为：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

说明：

- 当前主 chat persona 每轮成功完成后都会进入 memory 后处理，所以 `memory.extract` 和 `memory.compress` 通常都会出现。
- `memory.compress` 不代表一定发生压缩，需要看 `memory.compressed`。多数短请求应为 `false`。
- 如果 LLM 决定调用工具，会额外出现 `tool.execute`，并通常出现第二轮 `prompt.assembly` 和 `gen_ai.chat`。
- `TracingOkHttpInterceptor` 不创建独立 HTTP span，而是把 `http.*` 字段写到当前活跃的 `gen_ai.chat` span。

## 3. 性能记录方法

每个 case 建议先执行 1 次 warm-up，再连续执行 5 次记录数据。记录表如下：

| 指标 | 记录位置 | 说明 |
|------|----------|------|
| 客户端端到端耗时 | 测试客户端发送前后打点 | 从发送 AIDL 请求到收到 `AgentResponse` |
| `agent.request` duration | Phoenix root span | 代表 Service 侧完整处理时间 |
| `gen_ai.chat` duration | Phoenix LLM span | 主要由模型网络请求和模型生成耗时决定 |
| `http.duration_ms` | `gen_ai.chat` attribute | DashScope HTTP 请求耗时 |
| `tool.execute` duration | Phoenix tool span | 工具执行耗时，虚拟车辆状态机通常应很短 |
| `memory.extract` duration | Phoenix memory span | 记忆提取 LLM 调用耗时 |
| `memory.compress` duration | Phoenix memory span | 未压缩时应很短；压缩时包含摘要 LLM 调用 |
| Phoenix 可见延迟 | 收到响应到 Phoenix 展示 trace 的时间 | 受 BatchSpanProcessor batch timeout 影响，开发配置通常约 2 秒量级 |
| span 数量 | Phoenix trace tree | 判断链路是否缺 span 或重复异常 |

建议性能判断标准：

- 普通问答不应触发 Service 层 15 秒 timeout。
- 非 timeout case 的 `agent.request` 必须闭合，不应长期处于进行中状态。
- 同类 prompt 连续 5 次执行，span 树结构应基本稳定。
- `tool.execute` 对虚拟车辆状态机操作不应成为主要耗时来源。
- `memory.compress` 在未触发压缩时应记录 `memory.compressed=false`，并且耗时明显低于一次 LLM 调用。
- 如果具备切换 trace off 的构建方式，可额外对比 trace on/off 的客户端端到端耗时；在 LLM 网络调用占主导的场景下，trace 额外开销不应成为主要耗时。

## 4. 测试用例总览

| 编号 | 目标 | 主要验证 span |
|------|------|---------------|
| T01 | 普通问答基线 | `agent.request`、`prompt.assembly`、`gen_ai.chat`、`memory.*`、`response.dispatch` |
| T02 | 脱敏与截断 | `user.input`、`prompt.chat_messages`、`memory.prompt` |
| T03 | 单类车辆工具调用 | `tool.execute`、二轮 `gen_ai.chat` |
| T04 | 多工具调用 | 多个 `tool.execute`、多轮 loop |
| T05 | 车窗/天窗工具 | tool 参数和结果 |
| T06 | 座椅/方向盘工具 | tool 参数和结果 |
| T07 | 安全拦截 | `tool.safety_veto`、`tool.safety_veto_reason` |
| T08 | 参数校验失败 | `tool.output` 中的业务错误 |
| T09 | 外部天气工具 | `getWeatherForecast` tool span |
| T10 | 长期记忆提取与注入 | `memory.extract`、下一轮 `prompt.assembly` |
| T11 | Memory 压缩压力 | `memory.compress`、`memory.compressed=true` |
| T12 | Timeout 生命周期 | `response.dispatch`、root status error、first response wins |
| T13 | LLM HTTP 错误 | `gen_ai.chat` error、HTTP attribute |

## 5. 详细测试用例

### T01 普通问答基线

发送 prompt：

```text
你好，请用一句话介绍你能为车主提供哪些帮助。不要调用任何车辆控制功能。
```

预期响应：

- 返回普通文本。
- 不应执行车辆控制工具。

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| Span | 预期内容 |
|------|----------|
| `agent.request` | `input.type=TEXT`，`response.success=true`，`response.text.length>0` |
| `prompt.assembly` | `prompt.message_count>0`，`prompt.tool_spec_count>0`，`prompt.chat_messages` 包含本轮用户输入 |
| `gen_ai.chat` | `gen_ai.provider=dashscope`，`gen_ai.model=qwen-turbo`，有 `gen_ai.output`，最好有 token usage |
| `memory.extract` | `memory.operation=extract`，`memory.prompt` 可见，`memory.candidate_count` 可为 0 |
| `memory.compress` | `memory.operation=compress`，短会话通常 `memory.compressed=false` |
| `response.dispatch` | `response.success=true`，`response.text.length>0` |

性能观察：

- 记录 `agent.request` duration、`gen_ai.chat` duration、`memory.extract` duration。
- 如果该 case 都接近 15 秒 timeout，优先排查模型网络、API key 或 Phoenix/exporter 环境，不要先判断 trace 本身性能异常。

### T02 脱敏与长文本截断

发送 prompt：

```text
请记住我的手机号是 13812345678，我常用的车内温度是 22 度。请只回复“收到”。
```

预期响应：

- 返回简短确认文本。
- 不需要 tool。

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| 字段 | 预期 |
|------|------|
| `agent.request.user.input` | 手机号应显示为类似 `138****5678` |
| `prompt.assembly.prompt.chat_messages` | 手机号应脱敏 |
| `memory.extract.memory.prompt` | 手机号应脱敏 |
| `memory.extract.memory.output` | 如果模型输出手机号，也应脱敏 |
| `memory.extract.memory.candidate_count` | 可能大于 0，至少应存在该字段 |

通过标准：

- Phoenix 中不应出现完整 `13812345678`。
- `user.input.length` 仍应反映原始输入长度，而不是脱敏后长度。

### T03 空调单类工具调用

发送 prompt：

```text
打开空调，把主驾温度调到 22 度，风量调到 3 档。
```

预期响应：

- 返回已执行或已设置的自然语言回复。
- 模型大概率调用空调相关工具。

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat                  gen_ai.tool_calls 包含空调工具
├── tool.execute                 set_ac_status
├── tool.execute                 set_ac_drive_temp
├── tool.execute                 set_ac_fan_intensity
├── prompt.assembly              工具结果回填
├── gen_ai.chat                  最终回复
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| Span | 预期内容 |
|------|----------|
| 第一段 `gen_ai.chat` | `gen_ai.tool_calls` 包含 `set_ac_status`、`set_ac_drive_temp`、`set_ac_fan_intensity` 中的一个或多个 |
| `tool.execute` | `tool.name` 与调用工具一致 |
| `tool.execute` | `tool.arguments` 中能看到 `true`、`22`、`3` 等参数，按 200 字符限制截断 |
| `tool.execute` | `tool.success=true`，`tool.safety_veto=false` |
| 第二段 `prompt.assembly` | `prompt.chat_messages` 包含 tool result |
| 第二段 `gen_ai.chat` | `gen_ai.output` 是面向用户的最终回复 |

复测提示：

- 如果模型只回复说明而未调用工具，把 prompt 改成：“请立即执行车辆控制工具：打开空调，把主驾温度调到 22 度，风量调到 3 档。”

### T04 多工具组合调用

发送 prompt：

```text
我有点冷，请打开空调，主驾温度设为 24 度，打开左前座椅加热，同时打开方向盘加热。
```

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute                 set_ac_status
├── tool.execute                 set_ac_drive_temp
├── tool.execute                 set_seat_fl_heat
├── tool.execute                 set_steering_heat
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

- 是否出现多个 `tool.execute`。
- 每个 `tool.execute.agent.iteration` 是否相同或符合 loop 轮次。
- 每个 tool 的 `tool.arguments` 是否符合用户意图。
- `response.dispatch.response.success=true`。

性能观察：

- 记录 `tool.execute` 总耗时和单个工具耗时。
- 虚拟车辆状态机工具通常应远快于 `gen_ai.chat`。

### T05 车窗、天窗和后排开窗权限

发送 prompt：

```text
把左前车窗降到 30%，天窗打开到 40%，并禁止后排乘客开窗。
```

预期工具：

- `set_fl_window_status`
- `set_top_window_status`
- `set_no_window_opening_passengers`

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute                 window related
├── tool.execute                 window related
├── tool.execute                 window related
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| 字段 | 预期 |
|------|------|
| `tool.name` | 包含上述车窗/天窗工具 |
| `tool.arguments` | 左前车窗参数约为 `30`，天窗参数约为 `40`，禁止后排开窗为 `true` |
| `tool.output` | 包含虚拟车辆状态机返回结果 |
| `tool.success` | `true` |

### T06 座椅和方向盘舒适功能

发送 prompt：

```text
打开左前座椅加热，把右前座椅通风调到 60%，再打开方向盘加热。
```

预期工具：

- `set_seat_fl_heat`
- `set_seat_fr_air`
- `set_steering_heat`

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute
├── tool.execute
├── tool.execute
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

- `prompt.tool_specs` 中应能看到座椅相关工具声明。
- `tool.arguments` 中通风百分比应接近 `60`。
- `tool.output` 应是普通业务结果，不应被误标记为 trace error。

### T07 安全拦截：行驶中解锁车门

该 case 需要复用同一个 `sessionId`，分两步执行。

第 1 步发送 prompt：

```text
把当前车速设置到 60 公里每小时。
```

第 1 步预期工具：

- `set_vehicle_spd`

第 2 步发送 prompt：

```text
现在帮我解锁车门。
```

第 2 步预期工具：

- `set_door_lock`

第 2 步预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute                 set_door_lock
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| 字段 | 预期 |
|------|------|
| `tool.name` | `set_door_lock` |
| `tool.arguments` | 解锁语义，通常为 `false` |
| `tool.safety_veto` | `true` |
| `tool.safety_veto_reason` | 应说明车速或安全原因 |
| `tool.success` | `false` |
| `tool.output` | 包含 `[SAFETY VETO]` 或安全拦截说明 |
| `response.dispatch.response.success` | 通常仍可能为 `true`，因为 Agent 成功给出安全拒绝回复 |

复测提示：

- 如果第 2 步没有触发 safety veto，先在 Phoenix 或日志中确认第 1 步 `set_vehicle_spd` 是否执行成功，再复用同一个 session 发送第 2 步。

### T08 参数校验失败：超范围空调温度

发送 prompt：

```text
把主驾空调温度调到 45 度。
```

预期工具：

- `set_ac_drive_temp`

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute                 set_ac_drive_temp
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

| 字段 | 预期 |
|------|------|
| `tool.arguments` | 温度参数为 `45` |
| `tool.output` | 应包含参数非法、范围错误或执行失败类业务信息 |
| `tool.success` | 可能仍为 `true`，因为当前 `tool.success` 表示未被 safety veto，不等同于业务执行成功 |
| `tool.safety_veto` | `false` |
| `gen_ai.chat` status | 不应因为业务参数校验失败而被标记为 HTTP/LLM error |

该 case 用于区分“业务工具返回失败”和“trace/span 失败”。如果未来希望 `tool.success` 反映业务执行结果，需要单独调整工具结果协议。

### T09 外部天气工具

发送 prompt：

```text
帮我查询北京明天的天气预报，并根据天气给出一句驾驶建议。
```

预期工具：

- `getWeatherForecast`

预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── tool.execute                 getWeatherForecast
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

重点检查：

- `tool.name=getWeatherForecast`。
- `tool.arguments` 包含城市或天气查询参数。
- `tool.output` 包含天气结果或天气接口错误信息。
- DashScope 模型调用的 `gen_ai.chat` 应有 `http.duration_ms`。
- 天气工具内部 HTTP 是否出现独立 HTTP trace 不作为当前通过条件；当前方案重点记录 tool 的输入和输出。

### T10 长期记忆提取与下一轮注入

该 case 需要复用同一个 `sessionId`，分两步执行。

第 1 步发送 prompt：

```text
请记住：我喜欢车内温度保持在 22 度，开车时尽量使用小风量，不喜欢太强的风直吹。
```

第 1 步预期 trace：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress
└── response.dispatch
```

第 1 步重点检查：

| 字段 | 预期 |
|------|------|
| `memory.extract.memory.prompt` | 包含用户偏好提取任务 |
| `memory.extract.memory.output` | 可能包含 JSON 数组 |
| `memory.extract.memory.candidate_count` | 理想情况下大于 0；如果为 0，需要看模型输出是否为空数组 |

第 2 步发送 prompt：

```text
你还记得我喜欢什么样的空调设置吗？
```

第 2 步重点检查：

- `prompt.assembly.prompt.chat_messages` 或 system prompt 相关内容中应能看到长期记忆上下文的痕迹。
- `gen_ai.output` 应能回答 22 度、小风量、不直吹等偏好。
- 如果没有注入偏好，检查第 1 步 `memory.candidate_count` 和长期记忆持久化是否成功。

### T11 Memory 压缩压力测试

该 case 需要复用同一个 `sessionId`，连续发送多轮长文本。每轮 requestId 递增，例如 `trace-t11-001` 到 `trace-t11-006`。

每轮发送 prompt 模板：

```text
这是第 N 轮压缩压力测试。请只回复“收到第 N 轮”。下面是一段用于累积上下文长度的驾驶偏好说明：
我经常在早高峰从家出发去公司，希望车内温度保持在 22 度，风量保持在 2 档，不要让风直吹面部。遇到雨天时优先打开前风挡除霜，遇到空气质量差时切换内循环。夜间驾驶时希望座椅加热保持开启但不要太热。以上内容请不要展开解释，只回复确认。
```

执行方式：

- 将 `N` 从 1 递增到 8。
- 如果 8 轮后仍未触发压缩，可以继续发送到 10 到 12 轮，或适当加长每轮偏好说明。

预期 trace：

短期内每轮都会出现：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress              memory.compressed=false
└── response.dispatch
```

达到阈值后某一轮应出现：

```text
agent.request
├── prompt.assembly
├── gen_ai.chat
├── memory.extract
├── memory.compress              memory.compressed=true
└── response.dispatch
```

重点检查：

| 字段 | 预期 |
|------|------|
| `memory.compress.memory.input_chars` | 随着轮次增长逐步变大 |
| `memory.compress.memory.compressed` | 前几轮为 `false`，超过阈值后出现 `true` |
| `memory.compress.memory.prompt` | 触发压缩时包含对话历史摘要 prompt |
| `memory.compress.memory.output` | 触发压缩时包含摘要内容 |
| `memory.compress.memory.output_chars` | 触发压缩时大于 0 |

性能观察：

- 未压缩轮次的 `memory.compress` 耗时应很短。
- 触发压缩的轮次会额外调用摘要模型，`agent.request` duration 明显增加是正常现象。
- 如果触发 Service 15 秒 timeout，说明压缩压力用例过重，需要降低单轮文本长度或减少同时触发的复杂 tool 操作。

### T12 Timeout 生命周期

发送 prompt：

```text
请生成一篇不少于 8000 字的中文长文，详细介绍智能座舱、车辆控制、长期记忆、工具调用、安全策略和可观测性，每一部分都要展开说明。
```

预期结果：

- 如果模型响应超过 Service 的 15 秒 timeout，应返回 timeout 响应。
- 如果没有触发 timeout，可把字数要求提高到 15000 字，或在测试环境中临时降低网络质量。

预期 timeout trace：

```text
agent.request status=ERROR
└── response.dispatch response.success=false response.error_type=TIMEOUT
```

可能同时存在仍在后台完成的 `gen_ai.chat` span，但 root 最终状态不应被晚到的成功结果覆盖。

重点检查：

| 字段 | 预期 |
|------|------|
| `agent.request` status | `ERROR` |
| `response.dispatch.response.success` | `false` |
| `response.dispatch.response.error_type` | `TIMEOUT` |
| root `response.success` | `false` |
| root `response.error_type` | `TIMEOUT` |
| root 是否闭合 | 用户收到 timeout 后应能在 Phoenix 看到闭合 trace |

通过标准：

- timeout 后不应出现 root status 被后续成功覆盖。
- 不应出现同一个 root 下多个 `response.dispatch` 都成功生效。

### T13 LLM HTTP 错误

该 case 需要人为制造模型 HTTP 错误，prompt 本身可以使用任意简单文本。

发送 prompt：

```text
你好，请简单回复“测试 HTTP 错误链路”。
```

制造错误的方式任选一种：

- 断开设备网络。
- 使用无效 DashScope API key 的测试包。
- 将模型服务 endpoint 指向不可达地址的测试构建。

预期 trace：

```text
agent.request status=ERROR
├── prompt.assembly
├── gen_ai.chat status=ERROR
│   ├── http.request.method
│   ├── url.full
│   ├── server.address
│   ├── http.duration_ms
│   ├── error.type
│   └── error.message
└── response.dispatch response.success=false
```

重点检查：

| 字段 | 预期 |
|------|------|
| `gen_ai.chat.error.type` | IOException 类名或 HTTP 错误码 |
| `gen_ai.chat.error.message` | 网络错误或 HTTP 错误描述 |
| `gen_ai.chat.http.duration_ms` | 即使 IOException 也应有 |
| `agent.request.response.success` | `false` |
| `response.dispatch.response.error_type` | `MODEL_CALL_FAILED`、`EXCEPTION` 或实际错误类型 |

## 6. 完整性验收清单

执行完上述 case 后，用下表做最终验收：

| 检查项 | 通过标准 |
|--------|----------|
| root span | 每个 `TEXT` 请求都有且只有一个 `agent.request` root span |
| root 闭合 | 成功、失败、timeout 后 root 都能闭合 |
| 请求元数据 | root 上有 `request.id`、`session.id`、`source.app`、`input.type`、`user.input.length` |
| prompt 组装 | 普通请求和 tool 后续轮次都有 `prompt.assembly` |
| LLM 调用 | 每次模型调用都有 `gen_ai.chat`，并记录 model/provider/output |
| HTTP 信息 | DashScope 调用能看到 `http.duration_ms`，错误时能看到 `error.*` |
| tool 调用 | 车辆控制类 prompt 能看到 `tool.execute`、`tool.name`、`tool.arguments`、`tool.output` |
| safety | 行驶中解锁车门能看到 `tool.safety_veto=true` |
| memory extract | 成功主 chat 请求后能看到 `memory.extract` |
| memory compress | 短会话 `memory.compressed=false`，长会话压力下可触发 `true` |
| response dispatch | 每个请求都有 `response.dispatch`，root 上同步有 response summary |
| 脱敏 | 手机号等敏感内容不会完整出现在内容类 attribute |
| 性能 | 非压力 case 不触发 15 秒 timeout；tool span 不成为主要耗时来源 |

## 7. 建议记录模板

每个 case 建议记录一行：

| Case | requestId | sessionId | 响应成功 | root duration | LLM duration | HTTP duration | tool span 数 | memory.extract | memory.compress | Phoenix 延迟 | 结论 |
|------|-----------|-----------|----------|---------------|--------------|---------------|-------------|----------------|-----------------|--------------|------|
| T01 | trace-t01-001 | trace-basic-001 | true |  |  |  | 0 | yes | false |  |  |
| T03 | trace-t03-001 | trace-tool-ac-001 | true |  |  |  | 1-3 | yes | false |  |  |
| T07-2 | trace-t07-002 | trace-safety-001 | true |  |  |  | 1 | yes | false |  |  |
| T11-8 | trace-t11-008 | trace-compress-001 | true |  |  |  | 0 | yes | true/false |  |  |
| T12 | trace-t12-001 | trace-timeout-001 | false |  |  |  | 0 | optional | optional |  |  |

## 8. 结论判断

如果 T01 到 T11 均满足预期，且 T12/T13 能在异常环境下正确显示错误状态，则当前 trace 系统可以认为满足主 Agent demo 阶段的调试要求。

如果出现以下问题，需要回到代码层排查：

- Phoenix 中看不到 `agent.request`。
- root span 长时间不闭合。
- timeout 后 root status 被成功结果覆盖。
- tool prompt 能正常执行工具，但没有 `tool.execute`。
- `gen_ai.chat` 有 HTTP 异常但缺少 `error.type` 或 `http.duration_ms`。
- 内容类 attribute 出现完整手机号。
- `response.dispatch` 缺失，或 root 上没有 response summary。
