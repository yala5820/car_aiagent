# Context 模块现状、功能与完成度审计

**更新时间：** 2026-07-15

**适用范围：** 当前 `dev_runtime` 工作树中的 TEXT 生产链

**审计依据：** 生产代码、Context/Memory/Runtime/Tool/Trace 测试、全量 JVM 测试、Debug APK 构建和 Android Lint

## 一、总体结论

Context 已经是 TEXT Agent 的统一模型输入控制层，不再是旁路观测器，也不是简单的字符串拼接工具。当前所有发送给 TEXT LLM 的 `ChatMessage` 和 `ToolSpecification` 都由 `ContextOrchestrator + ContextMessageAssembler` 生成，`TextAgentLoopOrchestrator` 只消费装配结果。

本轮完成了此前遗留的 13 类问题收口：完整 Session 历史、工具交换校验、集中策略、可选上下文裁剪、真实 Memory 摘要压缩、一次性重装配、结构化 Tool outcome、最终回复写回一致性、FULL_DEBUG 全文 Trace、Token 估算校准字段以及 Context Legacy 类型清理。

当前最准确的状态是：

> **TEXT Context 的代码改造和自动化门禁已经基本完成；Automotive 模拟器已通过 AIDL、基础 TEXT 和 SQLite 冒烟，但仍缺目标车机长会话、Qwen token usage 和 Phoenix 展示验收。VOICE、Scene、VLM 等非 TEXT 兼容链仍保留旧输入路径。**

## 二、职责与边界

### 2.1 Context 负责什么

Context 负责管理模型输入，而不是吞并业务模块：

1. 调用各 Provider 获取 Prompt、当前用户、会话历史、长期记忆、工具、车辆、时间等上下文。
2. 用统一 `ContextContribution` 表达来源、可见性、信任级别、优先级、生命周期和 required 语义。
3. 按集中策略决定哪些内容可入模、可降级、可裁剪或必须失败关闭。
4. 装配最终 `SystemMessage`、Context Data、SessionMemory、CURRENT_USER 和工具规格。
5. 校验消息顺序、Tool Call/Tool Result 对应关系和唯一 System/CURRENT_USER 约束。
6. 估算输入 token，执行可选 Context Data 裁剪，并在必要时请求 Memory 压缩。
7. 输出稳定错误、预算报告、Contribution 决策和完整 Trace。

### 2.2 Context 不负责什么

| 能力 | 实际所有者 | Context 的职责 |
|---|---|---|
| Prompt 模板加载和渲染 | `PromptManager` | 选择调用并接收渲染结果 |
| Session/长期记忆存储 | `MemoryOrchestrator`、Store | 请求快照并决定如何入模 |
| 摘要生成和原子写回 | `MemoryCompressor`、`MemoryOrchestrator` | 判断何时压缩、给出目标预算、最多重试一次 |
| Tool 注册和反射执行 | `ToolRegistry`、`ToolDispatcher` | 获取 ToolSpecification 并交给模型 |
| ToolGroup 选择 | `AgentRuntime`、`ToolGroupSelector` | 消费结构化选择结果，不自行扩大工具集合 |
| 车辆状态读取 | `VehicleStateMachine` 适配入口 | 在工具组要求时调用并注入 |
| LLM 调用和循环推进 | `TextAgentLoopOrchestrator` | 交付合法且预算内的最终请求 |
| 工具安全审核 | `ToolSafetyEngine` | 不参与安全决策 |

这个边界符合当前自研 Agent 架构：LangChain4j 提供消息、模型和 Tool Calling 原语，AIAgent 的 Runtime、Context 和 AgentLoop 负责高度定制的业务编排。

## 三、生产调用链

```text
AIAgentService.processAgentRequest(TEXT)
  -> AgentRuntime.startSession()
       -> IntentRouter
       -> ToolGroupSelector
       -> RequestSession
  -> AgentRuntime.execute()
       -> ContextOrchestrator.prepare()
            -> 8 个 REQUEST_STATIC Provider
            -> ContextFrame + ContextPrepareResult
       -> TextAgentLoopOrchestrator.execute()
            -> 每轮 ContextOrchestrator.assemble()
                 -> 3 个 ITERATION_DYNAMIC Provider
                 -> ContextMessageAssembler
                 -> 可选 Context Data 裁剪
                 -> 必要时 Memory 压缩 + 动态 Provider 重读 + 二次装配
                 -> ContextAssemblyResult
            -> ChatRequest(messages, toolSpecifications)
            -> ModelCaller
            -> ToolSafetyEngine / ToolRegistry
            -> PostProcessor / Terminator / ResultCollector
```

TEXT 主链中不存在由 PreProcessor、AgentLoop 或 ModelCaller 另行拼接消息的第二条路径。`AgentLoopOrchestrator.execute(String, Map)` 和旧 PreProcessor 仍服务于非 TEXT 兼容入口，未纳入本轮删除范围。

本轮没有修改 AIDL、Parcelable 或外部 App 接口。调用方仍使用现有 `processAgentRequest(AgentRequest)` 和会话管理接口，不需要理解 Provider、Contribution、预算或压缩对象。

## 四、核心数据模型

### 4.1 RequestSession

`RequestSession` 是 Context 的请求级事实来源，包含 requestId、clientMessageId、userId、sessionId、personaId、输入、Intent、ToolGroup、deadline、取消状态和 TraceContext。Context 不重复做身份解析或意图识别。

### 4.2 ContextFrame

`ContextFrame` 只保留：

- 请求身份与规范化输入
- Intent/ToolGroup 结构化结果
- 请求级静态 Contributions

旧的 `sections`、`ContextDebugInfo`、`memorySummary`、`vehicleStateSnapshot`、`timeContext`、`promptContext`、`renderedExtraContext` 和 `tokenEstimate` 已删除，避免 Contribution 之外再出现第二套上下文事实。

### 4.3 ContextContribution

| 类型 | 数据 | 最终位置 |
|---|---|---|
| `TextContextContribution` | 文本 + targetArea | System 或 Context Data |
| `MessageContextContribution` | `List<ChatMessage>` + messageSource | SessionMemory 或 CURRENT_USER |
| `ToolContextContribution` | `List<ToolSpecification>` + selectionMode | ChatRequest 工具规格 |

所有生产 Contribution 都由 `ResolvedContextPolicy` 提供统一属性，不再由各 Provider 独立复制 required、visibility、priority、trust 和 lifecycle 常量。

### 4.4 装配状态对象

- `ContextAssemblyDraft`：一次装配使用的 Contribution 快照。
- `ContextAssemblyAttempt`：候选结果、是否建议压缩、SessionMemory 目标预算和 Contribution 决策。
- `ContextContributionDecision`：produced、included、trimmed、原因和 attemptIndex。
- `ContextAssemblyResult`：最终消息、工具、预算、错误、压缩状态和 Provider outcomes。

超预算失败结果不再携带可发送的消息，防止调用方误用失败候选继续调用模型。

## 五、Provider 与集中策略

### 5.1 REQUEST_STATIC Provider

| Provider | sourceKey | 作用 | 策略 |
|---|---|---|---|
| RuntimeContextProvider | runtime | 请求身份诊断 | required、POLICY_ONLY |
| PersonaContextProvider | persona | Persona 诊断 | optional、POLICY_ONLY |
| PromptContextProvider | prompt | 唯一 System Prompt | required、不可裁剪 |
| UserInputContextProvider | current_user | 唯一当前用户消息 | required、不可裁剪 |
| IntentContextProvider | intent | Intent 诊断 | optional、POLICY_ONLY |
| ToolGroupContextProvider | tool_group | 解析 ToolSpecification | CHAT_ONLY optional；SELECTED required |
| LongTermMemoryContextProvider | long_term_memory | 当前 user 的长期记忆 | optional、可裁剪、读取失败为 FALLBACK |
| CallerExtraContextProvider | caller_extra | 调用方附加上下文 | optional、可裁剪 |

### 5.2 ITERATION_DYNAMIC Provider

| Provider | sourceKey | 作用 | 策略 |
|---|---|---|---|
| SessionMemoryContextProvider | session_memory / session_memory_summary | 每轮重读完整会话历史及摘要 | required、不可普通裁剪 |
| VehicleStateContextProvider | vehicle_state | 每轮读取车辆状态 | 工具要求 `vehicle_status` 时 required 且入模 |
| TimeContextProvider | time | 每轮刷新当前时间 | optional、可裁剪 |

### 5.3 ContextPolicies

`ContextPolicies` 是生产 source 的唯一资格注册表。`ContextOrchestrator` 在 Provider 执行后校验 Provider 和 Contribution 是否与集中策略一致；未知 sourceKey 或属性漂移会直接失败，不允许悄悄进入模型。

当前安全裁剪只允许删除 `MODEL_VISIBLE + TARGET_CONTEXT_DATA + trimEligible + 非 required` 的文本贡献，并按 `OPTIONAL -> NORMAL -> HIGH` 顺序处理。Prompt、CURRENT_USER、SessionMemory、摘要、required 数据和工具规格不会被普通裁剪。

## 六、最终消息装配

`ContextMessageAssembler` 是纯函数组件，不访问数据库、Android Context、模型或 Trace。固定顺序为：

```text
SystemMessage
Context Data UserMessage（有内容时）
SessionMemory ChatMessage 序列
CURRENT_USER UserMessage（仅 iteration 0）
```

Context Data 使用带来源、信任级别和数据边界的 envelope，长期记忆、摘要和 caller extra 作为数据进入 UserMessage，不会伪装成 System 指令。

iteration 1+ 不再追加静态 CURRENT_USER；用户消息已经在 iteration 0 装配成功、预算通过且取消检查通过后写入 SessionMemory，工具迭代从动态历史中读取它。

`ContextMessageSequenceValidator` 校验最终请求；`SessionHistorySequenceValidator` 在 Memory 边界校验持久化历史。后者精确匹配 Tool Call ID 和工具名，仅修复进程中断造成的尾部未闭合 turn，中间损坏直接失败。

## 七、Memory 与长会话恢复

### 7.1 完整历史

`PersistentSessionChatMemory` 不再按固定 50 条淘汰消息。`SessionChatMemoryProvider` 中的 50 只表示缓存会话数量兼容参数，不再限制单个会话历史长度。Store 写入和 Context 快照均保留完整 turn 与 ToolExchange。

SessionMemory 按 `sessionId` 隔离；同 session 切换 user 会保留短期会话历史，但长期记忆由当前 `userId` 重新读取；切换 session 后不会携带旧 session 历史；切换 persona 会改变 Prompt，但不改变 session 的短期历史归属。

### 7.2 超预算恢复顺序

1. 装配并估算完整输入。
2. 逐项删除允许裁剪的 optional Context Data。
3. 仍超限且 SessionMemory 可压缩时，计算 SessionMemory 目标 token。
4. `MemoryOrchestrator` 按完整 turn 划分历史，至少保护最近 2 轮。
5. `MemoryCompressor` 调用摘要模型，将旧摘要和更早历史合并为单一新摘要。
6. 通过 compare-and-set 原子写回；若计划期间有新消息，返回 `STALE_PLAN`，不得覆盖新历史。
7. Context 重新运行动态 Provider并进行第二次装配。
8. 同一 Agent 请求跨所有 iteration 最多真正执行一次摘要压缩；仍超限则稳定失败。

压缩尚未调用摘要模型时不会标记 `compressionAttempted=true`。取消可发生在摘要前、摘要后写回前和二次装配前，失败或取消不会写入不完整候选。

摘要当前以头部 `UserMessage("【对话摘要】...")` 形式持久化，但 Provider 会将它从普通历史中分离，作为 `session_memory_summary` 的 UNTRUSTED Context Data 入模；再次压缩会替换旧摘要，不会嵌套多条摘要。

## 八、Tool 语义

ToolGroup 有三种 Context 模式：

- `NONE`：CHAT_ONLY，无工具。
- `SELECTED`：明确工具组，按上游 selectedToolNames 解析。
- `ALL_FALLBACK`：Demo 全量兜底，由 Registry 展开完整工具集合。

Context 不自行扩大工具范围。工具名缺失或 schema 无法解析时返回 `TOOL_SPEC_RESOLUTION_FAILED`。Runtime 允许结构合法的 Demo `allToolsFallback` 进入 Context；普通聚合组仍失败关闭。

需要注意：当前 `DefaultToolGroupSelector` 对 UNKNOWN 无弱车控关键词时返回 CHAT_ONLY，对弱车控关键词返回澄清，不主动产生 allToolsFallback。全量兜底能力和 Runtime/Context 链路已经可用，但是否由生产 Selector 触发仍属于 ToolGroup 匹配策略，未在本轮修改。

TEXT 工具执行只使用 `ToolRegistry.dispatchWithOutcome()` 的结构化结果：registered、argumentParseSuccess、invokeSuccess、dispatchSuccess、errorType 和 errorDetail。Trace 不再通过中文错误字符串猜测成功状态。

## 九、预算与 Token

当前 qwen-turbo Demo Profile：

| 项目 | 数值 |
|---|---:|
| 模型窗口 | 32768 tokens |
| 预留输出 | 2048 tokens |
| 安全余量 | 1024 tokens |
| 最大估算输入 | 29696 tokens |

`HeuristicContextTokenEstimator` 覆盖 System/User/Ai 正文、Tool Call 名称与参数、Tool Result、工具名称、描述和 parameters schema。估算仍不是 Qwen 官方 tokenizer，因此 Trace 同时记录 estimated input、模型返回的 actual input、差值、比率和 usage 是否可用，用于设备侧校准。

预算门禁保证：成功结果一定在预算内；失败结果不会调用主模型；当前用户消息不会在预算失败前污染 Memory。

## 十、Trace

目标 Trace 树：

```text
agent.request
  -> agent.loop
       -> context.prepare
            -> context.provider.*
       -> agent.iteration[n]
            -> context.assemble
                 -> context.provider.*
                 -> context.fragment.*
                 -> context.message.*
                 -> context.toolset
            -> gen_ai.chat
            -> tool.execute
                 -> tool.safety_check
                 -> tool.dispatch
                 -> tool.result_writeback
       -> memory.extract / memory.compress
  -> response.dispatch
```

当前 Trace 能看到：

- Provider 生命周期、required、status、耗时、错误和 `produced_model_visible`。
- 每个最终 Text fragment 的 source、target、正文和 included 状态。
- SessionMemory/CURRENT_USER 的完整消息角色与正文。
- 工具名称、description、parameters schema 和 selection mode。
- 最终 `gen_ai.request.messages` 与 `gen_ai.request.tool_specs`。
- 裁剪前后 token、每项 TrimAction、压缩推荐/执行/成功、写回快照匹配和重读状态。
- estimated/actual input token、delta、ratio 和 usage availability。
- Tool dispatch 和 writeback 的真实结构化成功状态。

Demo 的 `FULL_DEBUG` 直接写入原文，不进行业务层截断。Provider 的 produced 与最终 Contribution included 已分离；空文本、空消息、空工具以及 iteration 1+ 被排除的 CURRENT_USER 不再误报为已入模。

Phoenix/exporter 本身是否限制超长 attribute 尚未获得设备证据。如果平台限制内容长度，应记录为平台限制，不应重新在业务代码中静默截断。

## 十一、错误与取消

主要 Context 错误包括：required Provider 失败、工具规格解析失败、消息序列非法、预算超限、Memory 压缩失败、Context 取消和内部契约错误。

关键原则：

- required Provider 的 FALLBACK/FAILED 均终止当前请求。
- optional Provider 可明确 FALLBACK，不得伪装成 SUCCESS。
- 历史中间损坏硬失败，只有尾部中断可修复。
- 压缩候选无收益、超过目标、快照过期或摘要失败均不覆盖 Store。
- 取消和 deadline 检查贯穿 prepare、assemble、压缩、主模型和工具阶段。

## 十二、完成度评估

| 能力 | 当前状态 | 证据边界 |
|---|---|---|
| TEXT ChatMessage 唯一生成权 | 已完成 | 生产链和端到端 JVM 测试 |
| TEXT ToolSpecification 唯一生成权 | 已完成 | CHAT_ONLY、明确工具、allToolsFallback 测试 |
| Provider 真实接入 | 已完成 | 8 静态 + 3 动态 Provider |
| 集中 ContextPolicy | 已完成 | 全生产 source 注册与一致性校验 |
| Session/User/Persona 语义 | 代码完成 | 模拟器同 Session 召回、新 Session 隔离、不同用户长期记忆隔离通过；同 sessionId 换 user 待专用客户端验收 |
| 完整 Session 历史 | 已完成 | 100 条内存 Store 测试；模拟器 SQLite 52 条消息、序列错误 0、首尾均保留 |
| ToolExchange 校验与尾部修复 | 已完成 | ID/名称/中间损坏/尾部修复测试 |
| optional Context Data 裁剪 | 已完成 | 预算测试和装配决策测试 |
| 生产自动压缩与一次重试 | 代码完成 | 真实 Orchestrator + Memory Gateway 集成测试；真实摘要模型待设备验收 |
| CAS 防并发覆盖 | 代码完成 | 通用 Store 替身测试；SQLite 事务待设备验收 |
| Tool outcome 结构化 | 已完成 | 参数失败、未注册和 invoke 失败测试 |
| PostProcessor/Memory 一致性 | 已完成 | 最终文本写回测试 |
| FULL_DEBUG 全文 Trace | 代码完成 | 超 10KB JVM attribute 测试；Phoenix 待验收 |
| Token 估算与误差字段 | 基础完成 | 单调性/usage 字段测试；真实 Qwen 校准未完成 |
| Context Legacy 清理 | 已完成 | 旧类型/API 无生产引用，编译与 Lint 通过 |
| 非 TEXT 输入统一接管 | 未纳入本轮 | VOICE/Scene/VLM 保留旧链 |

不再使用主观百分比代替证据。代码层与自动化层已经完成，设备层是否通过必须在连接车机后单独签字。

## 十三、当前遗留问题与风险

### P1：设备与 Phoenix 验收未完成

Android Automotive 模拟器现已在线，AIAgent Service、外部 TestApp AIDL 绑定、基础 TEXT 模型响应、同 Session 记忆召回、新 Session 隔离、不同用户长期记忆隔离，以及 SQLite 52 条消息不截断均已通过。尚未验证目标车机上的真实摘要模型、取消竞争、同 sessionId 换 user、持久化 ToolExchange、异常工具链和连续运行；本机也没有 Phoenix/Docker 服务，OTLP 导出到 `localhost:6006` 明确连接失败。该项仍是发布前验收缺口，不能由 JVM 测试或基础模拟器冒烟替代。

### P1：真实 Qwen token 偏差尚未校准

启发式估算器已经覆盖完整结构并提供 actual/estimated 对比字段，但仍需至少 10 个真实请求确认不存在超过 10% 的未解释低估。若出现系统性低估，应调整 Profile 安全系数，不应在 Context 中引入昂贵的逐请求第三方 tokenizer。

### P2：压缩摘要仍使用消息哨兵持久化

`【对话摘要】` 作为头部 UserMessage 已有严格唯一性和分离逻辑，当前可用；长期设计可考虑在 SQLite 中增加结构化 summary 字段，降低对文本前缀协议的依赖。该改动涉及数据库迁移，不属于本轮最小收口。

### P2：allToolsFallback 的生产触发取决于 Selector

Runtime、Context 和 Assembler 已支持并测试 allToolsFallback，但默认 Selector 当前不主动返回该状态。若产品要求“任何模糊指令都暴露全量工具”，需要另行修改 ToolGroup 匹配策略；本轮按计划边界未改匹配规则。

### P2：非 TEXT 仍有旧上下文链

Context 的独占权只适用于 TEXT。VOICE、Scene、VLM 和兼容 `AgentLoopOrchestrator` 仍可能由旧 PreProcessor 构造消息。项目若最终删除这些入口，应在删除业务入口时同步清理旧 AgentLoop/PreProcessor，而不是在 TEXT Context 改造中提前误删。

### P3：既存工程警告

构建仍报告 AndroidManifest 重复权限和部分 deprecated API warning；本轮未改 Manifest 或无关 API。它们不阻断 `assembleDebug` 和 `lintDebug`，但可在独立工程清理任务中处理。

### P2：快速连续请求的终态边界需要目标车机确认

设备冒烟发现：Session USER/AI 已落库并不代表上一请求的 MemoryExtractor 和终态分发已经完成。若测试客户端在约 2 秒内继续发送，上一请求后处理会被抢占，MemoryExtractor 报 `IOException: Canceled`，TestApp 显示“请求已取消”；每轮落库后额外等待 8 秒的 13 轮测试全部成功。该现象不影响 Context 消息合法性，但需要在目标车机上确认客户端的忙闲/终态协议，避免把“消息已写入”误当作“请求已完全结束”。

### P2：现有 TestApp 无法验收同 sessionId 切换 user

后端短期记忆 key 只使用 sessionId，`resolveSessionId()` 也允许为新 user 建立同 sessionId 的 metadata，因此代码和 JVM 测试具备“短期会话不变、长期用户记忆切换”的语义。但当前 TestApp 切换用户后会清空当前会话选择，只展示新用户自己的会话列表，不能把原 sessionId 继续传给 AIDL。模拟器已验证新 Session 不携带旧历史、`test_user_1` 不读取 `default_user` 的 Alice；同 sessionId 换 user 仍需专用 AIDL 客户端或 TestApp 支持显式复用 sessionId 后验收。

## 十四、验证基线

本轮最终门禁应以最后一次全量命令结果为准：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug --rerun-tasks
.\gradlew.bat :app:lintDebug
```

已完成的自动化覆盖包括：

- 普通连续 TEXT、Session/User/Persona 语义。
- 100 条历史保留、尾部修复、中间损坏和持久化异常。
- optional 裁剪、required 保留、工具 schema 超限。
- 压缩成功、无可压缩 turn、一次限制、摘要替换和 stale CAS。
- CHAT_ONLY、明确工具和 allToolsFallback。
- Tool 参数失败、invoke 失败、未注册、Safety 和 writeback。
- PostProcessor 最终写回一致性。
- Context/LLM/Tool/Memory Trace 层级、全文和 included/produced 语义。
- Token 估算单调性和 actual/estimated 误差字段。

设备冒烟已完成：Automotive 模拟器上的 Service 启动、TestApp AIDL 绑定、真实 DashScope TEXT 回复、同 Session 姓名召回、新 Session 隔离、不同用户长期记忆隔离，以及 52 条 USER/AI 消息落库且序列错误为 0，首尾历史均保留。

尚未完成的外部验收：目标车机 10 轮质量复核、持久化 ToolExchange、真实超预算摘要、真实 allToolsFallback、安全失败、Session/User 切换、压缩取消、快速请求终态协议、10 个 Qwen token 样本和 Phoenix 全链路检查。

## 十五、后续建议

1. 在目标车机按计划 Task 3.5 一次性完成长会话、工具、切换和取消验收，并在本机启动 Phoenix 后保存请求、traceId 和异常证据。
2. 根据真实 Qwen usage 只调整 `ModelContextWindowProfile` 的安全系数，暂不增加复杂配置或 tokenizer 依赖。
3. Demo 稳定后再决定是否把摘要迁移为 SQLite 结构化字段。
4. 等 VOICE/Scene/VLM 删除决策落地后，再统一删除非 TEXT 旧 AgentLoop 和 PreProcessor。

## 十六、最终结论

Context 的架构方向合理：它拥有 TEXT 模型输入的唯一装配权，调用 Prompt、Memory、Tool 和 Vehicle 模块，但不接管这些模块的内部算法和存储职责。预算、裁剪、压缩协调、消息合法性和 Trace 已形成完整闭环。

当前不需要再次重写 Context 架构。下一步重点不是继续增加抽象，而是完成真实车机/Phoenix 验收，并根据真实 token 数据做小范围校准。
