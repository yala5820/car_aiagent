# Context 模块剩余人工验收清单

> 状态：待人工执行  
> 日期：2026-07-15  
> 对应计划：[Context 剩余 13 项问题清理计划](../plan_overall/2026-07-13-context-remaining-13-issues-closure-plan.md) 的 Task 2.4、Task 3.5  
> 范围：只验收 TEXT Context 生产链路；不覆盖 VOICE、SCENE、VLM 兼容链路，也不在真实车辆上执行控制指令。

## 1. 本清单解决什么问题

自动化测试、构建和 Lint 已完成，但下列事实必须在真实 Android 请求、真实 DashScope 返回和 Phoenix 中确认：模型实际输入量、Provider 最终是否入模、工具是否真实写回历史、压缩是否真实调用摘要模型，以及客户端取消和切换行为是否符合约定。

本清单不以模型回复文本作为唯一证据。例如模型说“空调已打开”不代表工具真实执行；必须同时满足工具调用、工具结果、虚拟车辆状态或 SQLite 历史、以及 Trace 中对应字段的证据要求。

## 2. 验收前准备

### 2.1 基础环境

- [ ] 安装本轮 Context 改造后的 Debug APK，记录 APK versionCode、Git commit、设备型号和 Android 版本。
- [ ] 使用 Debug Trace 配置，确认业务层为 `FULL_DEBUG`，不要启用业务截断。
- [ ] 在电脑启动 Phoenix，并确认 6006 端口可访问；执行 `adb reverse tcp:6006 tcp:6006` 后发起一条 TEXT 请求，Phoenix 中必须能查询到该请求的 Trace。
- [ ] 使用 AIAgent 的 Demo `VehicleStateMachine` 执行工具和安全用例，禁止在真实车辆上执行。
- [ ] 每个独立用例新建 Session；需要验证历史的用例不得中途切换 Session。
- [ ] 每次请求记录：`requestId`、`clientMessageId`、`userId`、`sessionId`、输入、最终回复、`traceId`、时间和结论。
- [ ] 能查看 AIAgent 的 SQLite 数据库，至少能核对 `sessions` 和 `session_messages`；没有数据库访问权限时，该项验收不能判为通过。

### 2.2 额外测试能力

下列用例不能仅靠当前 TestApp 的普通聊天 UI 稳定复现，需要一个 Debug AIDL 测试客户端或等价测试钩子。该客户端只需要能传入既有 AIDL 请求字段并按指定时机发送/取消请求，不需要改动生产协议。

| 能力 | 对应用例 | 缺失时的处理 |
|------|----------|--------------|
| 指定已有 `sessionId` 并替换 `userId` 发起请求 | MA-07 | 记录为“客户端能力缺失”，不得判定 Context 失败 |
| 让 `RequestSession` 使用 `allToolsFallback` 选择结果 | MA-05 | 记录为“默认 Selector 不产生该结果”，不得用普通 UNKNOWN 聊天替代 |
| 在压缩计划生成和 CAS 写回之间插入第二条消息 | MA-08 | 记录为“缺少竞争复现钩子”，不得以普通串行聊天替代 |
| 在摘要模型调用前、摘要返回后分别取消当前请求 | MA-09 | 记录为“缺少可控取消时机”，不得只验证取消按钮 |

### 2.3 压缩触发方式

生产 `qwenTurboDemo()` 的输入预算为 29,696 Token。为避免用真实大段对话浪费模型调用额度，压缩相关用例推荐使用一份**不提交的 Debug 验收构建**：仅将 `ModelContextWindowProfiles.qwenTurboDemo()` 的预算临时调低，例如总窗口 4,096、预留输出 512、安全余量 256；验收结束后恢复文件并确认 Git diff 中没有该临时修改。

若不制作该 Debug 验收构建，则必须构造超过 29,696 Token 的真实 Session 历史，才能声称已触发生产预算下的压缩。不能因为历史消息达到 50 条就推断压缩发生。

## 3. 统一取证规则

每个用例保存一份记录，至少包含：

| 字段 | 要求 |
|------|------|
| 基本信息 | 用例编号、执行人、时间、设备、APK commit、userId、sessionId、requestId、traceId |
| 用户可见结果 | 原始输入、最终回复、是否只有一个终态回调 |
| 数据库证据 | 相关 Session 的消息总数、顺序、首尾记录；工具用例必须包含 Tool Call/Tool Result 是否落库 |
| Trace 证据 | Phoenix 截图或导出，包含 Span 树和本用例要求的关键属性 |
| 结论 | `PASS`、`FAIL` 或 `BLOCKED`；FAIL 必须写明实际值与预期值，BLOCKED 必须写明缺少的环境或测试能力 |

`PASS` 的前提是本用例所有预期均满足。模型回答“看起来合理”、只有日志、只有数据库记录、或者只有 Phoenix 中的工具 schema，都不足以单独判定工具或压缩成功。

## 4. 人工验收用例

### MA-01：十轮连续对话、质量和历史顺序

**目的**：确认真实 TEXT 请求稳定经过 Context，并且连续聊天不会出现历史乱序、重复终态或明显丢失上下文。

**操作**：

1. 创建新 Session，连续发送 10 条彼此有关联的普通 TEXT 请求；每条都等待上条的最终回调完成后再发送。
2. 第一条写入一个唯一事实，例如“本轮验收口令是 `CTX-715-A`，稍后请记住”。第 10 条询问该口令。
3. 查询该 Session 的 SQLite 消息顺序，并在 Phoenix 打开第 1、5、10 条请求。

**预期结果**：

- [ ] 每条请求恰有一个终态结果；没有无故取消、重复回复或交叉回复。
- [ ] 第 10 条能够正确召回 `CTX-715-A`，且没有把其他 Session 的内容带入。
- [ ] Session 的 USER/AI 消息顺序有效，没有孤立 ToolExecutionResult 或 AI ToolCall。
- [ ] 每条 Trace 具备 `agent.request -> agent.loop -> context.prepare/context.assemble -> gen_ai.chat` 主链路，且 `context.assemble` 中的 `tokens.estimated <= tokens.max`。

### MA-02：跨 50 条历史的真实 ToolExchange

**目的**：确认长历史中不会因为旧窗口逻辑丢失早期消息，并且工具调用与工具结果不会在边界处被拆散。

**操作**：

1. 创建新 Session，先完成 22 至 24 轮普通对话，使 SQLite 中累计接近 46 至 48 条 USER/AI 消息。
2. 发送一个已在 Demo 中稳定触发的、无风险的明确控车指令，例如空调开关或车窗开度设置；执行前记录虚拟车辆状态。
3. 确认这条请求真实产生 Tool Call 和 Tool Result 后，再继续普通对话直到该 Session 超过 50 条消息。
4. 在 SQLite、虚拟车辆状态和 Phoenix 中共同核对结果。

**预期结果**：

- [ ] 最早的普通对话仍保留，历史不是只保留末尾 50 条。
- [ ] 工具调用和对应 Tool Result 成对保留，Tool Call ID 和 tool name 能精确对应；不得存在孤立工具结果。
- [ ] 虚拟车辆状态与工具参数一致，最终回复不应在工具失败时宣称成功。
- [ ] Phoenix 中存在同一 traceId 下的 `gen_ai.tool_calls`、`tool.execute`、`tool.dispatch`、`tool.result_writeback`；`tool.success`、`tool.dispatch_success`、`tool.invoke_success` 和 `tool.outcome` 相互一致。

### MA-03：预算裁剪优先级

**目的**：确认超预算时优先裁剪 optional Context Data，不在压缩前静默删除 SessionMemory 或当前用户消息。

**操作**：

1. 使用第 2.3 节的 Debug 低预算构建，创建一个含长历史、长 Prompt、车辆状态和工具 schema 的 Session。
2. 发送一条当前用户问题，并在 Phoenix 查看第一次 `context.assemble`。
3. 同时核对 SQLite 历史和最终模型请求中的当前问题。

**预期结果**：

- [ ] 第一次 `context.assemble` 可见 `tokens.estimated`、`tokens.max`、`budget.within`，并能看到实际 TrimAction 或对应的 fragment/message/toolset 不再 included。
- [ ] 被裁剪的是可裁剪的 optional Context Data；SessionMemory 与最后一条 CURRENT_USER 不能在压缩之前被静默删除。
- [ ] 如果裁剪后预算仍超限，才进入 MA-04 的压缩流程；如果裁剪后已满足预算，则不应出现 `memory.compress`。

### MA-04：真实摘要压缩、重装配和摘要语义

**目的**：确认 Context 只协调压缩，Memory 实际调用摘要模型并以 CAS 写回；最多执行一次压缩后重装配。

**操作**：

1. 在 MA-03 的低预算场景中继续增加完整对话 turn，确保 optional 内容裁剪后仍超预算且至少存在 3 个完整 turn。
2. 发送一条新问题，等待最终响应；在 Phoenix 打开该请求的全部 Span，并查看对应 Session 的 SQLite 记录。
3. 用压缩后 Session 再问一个最近两轮中明确出现的事实。

**预期结果**：

- [ ] `context.assemble` 显示 `context.compression.recommended=true`、`context.compression.executed=true`、`context.compression.success=true`，并有 `tokens_before`、`tokens_after`、`snapshot_match=true`。
- [ ] 存在且只存在一个 `memory.compress` Span；它的 `memory.compressed=true`，并能看到摘要模型输入与摘要输出。
- [ ] SQLite 中只保留一个以 `【对话摘要】` 开头的摘要消息，旧历史被摘要替代，最近两个完整 turn 仍保留。
- [ ] 下一次装配把摘要作为历史 Context Data，而不是 CURRENT_USER；当前用户问题仍是最后一条用户消息。
- [ ] 压缩后的问题能继续得到正常响应，并能回答最近两轮事实。

### MA-05：CHAT_ONLY、明确工具和 allToolsFallback

**目的**：确认工具集由 Context 唯一装配，三种 ToolGroup 选择都符合预期。

**操作**：

1. 用普通闲聊触发 CHAT_ONLY，例如“讲一个简短笑话”。
2. 用已验证的明确控车指令触发明确工具组。
3. 通过第 2.2 节的 Debug 客户端显式构造 allToolsFallback 请求；不得试图用普通 UNKNOWN 输入替代。

**预期结果**：

- [ ] CHAT_ONLY 的 `context.toolset` 工具数量为 0，模型不发起 Tool Call。
- [ ] 明确工具组只提供该组工具，模型工具调用名称属于该工具集。
- [ ] allToolsFallback 的 `context.toolset` 包含注册表全量工具，Runtime 不因该选择结果拒绝请求。
- [ ] 三种场景的最终模型输入都来自 `ContextMessageAssembler`；不得出现旧 PreProcessor 额外注入工具的证据。

### MA-06：工具成功、非法参数、工具异常和 Safety veto

**目的**：确认工具结果的结构化语义和最终回复一致。

**操作**：

1. 执行一个稳定的无风险工具成功用例。
2. 发送一个非法参数指令，例如将车窗设置为 150%。
3. 通过 Demo 测试环境构造一个已知会抛出异常的工具调用。
4. 仅在虚拟车辆状态机中构造 Safety veto，例如车速大于阈值时请求解锁车门。

**预期结果**：

- [ ] 成功用例：工具执行、状态变化、Tool Result 和最终回复一致。
- [ ] 非法参数：状态不改变；若模型调用工具，`tool.argument_parse_success` 或 `tool.outcome` 明确表明失败；最终回复不得谎称成功。
- [ ] 工具异常：`tool.error_type`、`tool.error_detail` 和 `tool.outcome` 有真实错误语义，最终回复说明未完成。
- [ ] Safety veto：存在安全判定，未进入真实 `tool.dispatch`，状态不改变，拒绝结果被写回模型。

### MA-07：Session 与用户记忆隔离

**目的**：确认 Session 短期记忆和用户长期记忆按设计分别隔离。

**操作**：

1. 用户 A 的 Session S 写入短期事实和可提取的个人偏好。
2. 切换到用户 A 的新 Session S2，询问 S 中的短期事实。
3. 使用 Debug 客户端保留同一 sessionId S，但把 userId 切换为用户 B；分别询问短期事实和用户 A 的个人偏好。

**预期结果**：

- [ ] S2 不携带 S 的短期对话。
- [ ] 用户 B 使用同一 S 时仍可看到 S 的短期对话，但不能得到用户 A 的长期个人偏好。
- [ ] Trace 与数据库中的 sessionId/userId 与预期一致；没有错误复用其他用户长期记忆。

### MA-08：压缩计划过期（STALE_PLAN）

**目的**：确认压缩生成计划后若历史被新消息修改，旧计划不能覆盖新消息。

**操作**：

1. 使用低预算 Debug 构建触发压缩，在 `context.compression.recommended=true` 后、CAS 写回前通过第 2.2 节钩子向同一 Session 写入第二条新消息。
2. 等待第一个请求完成，再读取 SQLite 和 Phoenix。

**预期结果**：

- [ ] `context.compression.snapshot_match=false`，压缩结果为 STALE_PLAN 或等价失败语义。
- [ ] 第二条新消息仍完整存在，未被旧压缩计划覆盖或丢失。
- [ ] 第一个请求不会因为旧计划写回造成重复响应或破坏 Session 历史顺序。

### MA-09：压缩前后取消

**目的**：确认取消不会污染当前用户消息、不会写入半成品摘要，也不会产生重复终态。

**操作**：

1. 在低预算场景中，通过第 2.2 节钩子分别于摘要模型调用前、摘要模型返回但 CAS 写回前调用 `cancelAgentRequest`。
2. 对每个时机读取响应、SQLite 和 Phoenix。

**预期结果**：

- [ ] 每个 requestId 最多一个终态，且终态为取消而不是“取消后又成功”。
- [ ] SQLite 不写入半成品摘要；取消前已存在的历史保持有效顺序。
- [ ] Phoenix 显示 `cancelled_before_memory_compaction` 或 `cancelled_after_memory_compaction` 等对应原因；不得出现第二次压缩重试。

### MA-10：真实 Qwen Token 校准

**目的**：确认启发式估算没有超过 10% 的未解释低估，并为后续 Profile 安全系数调整保留证据。

**操作**：

1. 选择至少 10 个代表性 TEXT 请求：普通中文、含英文/JSON、短历史、长历史、明确工具、allToolsFallback、压缩前和压缩后等。
2. 从每条 Trace 记录 `gen_ai.usage.estimated_input_tokens`、`gen_ai.usage.input_tokens`、`gen_ai.usage.input_estimate_delta`、`gen_ai.usage.input_estimate_ratio`。
3. 对每条实际 input token 大于 0 的记录计算低估率：`(actual - estimated) / actual`。

**预期结果**：

- [ ] 每条真实模型响应都能拿到 actual usage；拿不到时记录模型/SDK 未返回 usage，不得伪造为 0。
- [ ] 不存在低估率大于 10% 且没有明确原因的样本。
- [ ] 不存在实际输入超过 `tokens.max` 或模型窗口的请求。
- [ ] 若发现系统性低估，记录样本并只调整 `ModelContextWindowProfiles` 的安全余量，不在业务链路增加 tokenizer 依赖或临时截断。

### MA-11：Phoenix 全链路与完整正文

**目的**：确认 Context Trace 可直接解释“哪个 Provider 产生了什么、哪些内容最终入模、预算和工具如何变化”。

**操作**：选择一条包含长 Prompt、SessionMemory、工具 schema 和一次工具调用的请求，在 Phoenix 展开全部 Span 与属性。

**预期结果**：

- [ ] `agent.request -> agent.loop -> agent.iteration -> context.prepare/context.assemble -> gen_ai.chat/tool.execute` 位于同一 traceId，父子关系正确。
- [ ] 每个 Provider 有独立 `context.provider.*` Span，`provider.produced_model_visible` 只表达“产生过可见内容”。
- [ ] 最终入模依据 `context.fragment.*`、`context.message.*`、`context.toolset` 的 included/消息/工具集字段判断；被裁剪内容不得仍显示为 included。
- [ ] `message.content`、`toolset.schema`、长 System Prompt、长 SessionMemory、工具参数和结果均能看到完整尾部内容，不应有业务层 `truncated` 标记。
- [ ] 工具字段 `tool.dispatch_success`、`tool.invoke_success`、`tool.success`、`tool.outcome` 与实际工具结果一致。
- [ ] 若 Phoenix/exporter 自身限制 attribute 长度，保存截图和长度证据，结论标为“平台限制”；不得据此要求业务代码重新静默截断。

## 5. 最终判定

| 结论 | 条件 |
|------|------|
| 人工验收通过 | MA-01 至 MA-11 均 PASS，或明确不适用的项已由等价、可重复的真实链路证明 |
| 人工验收失败 | 任一用例出现数据丢失、跨用户泄漏、工具回复与真实结果不一致、超预算后破坏当前消息、重复终态、Trace 语义错误或未解释 Token 低估 |
| 人工验收受阻 | Phoenix、数据库访问、目标设备或第 2.2 节 Debug 测试能力缺失；必须记录阻塞项，不能宣称“已通过” |

本清单执行后，请将每项的记录、Trace 截图/导出和数据库查询结果附在同一目录的独立验收结果文档中。只有该结果文档具备可追溯证据后，才可以关闭计划中的设备与 Phoenix 验收项。
