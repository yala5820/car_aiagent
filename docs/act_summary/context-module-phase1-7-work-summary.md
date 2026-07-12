# Context 模块 Phase 1-7 阶段工作总结

汇报日期：2026-07-08

汇报对象：项目经理 / AI Agent 项目负责人

## 一、阶段结论

本阶段我们已经为 AIAgent 引入了第一版统一 Context 模块。它不是简单增加一个提示词片段，而是在 `AgentRuntime` 和 `AgentLoopOrchestrator` 之间建立了一层“上下文装配与观测层”：

```text
AgentRequest
  -> AgentRuntime.startSession()
  -> RequestSession
  -> ContextOrchestrator.build()
  -> ContextFrame
  -> RuntimeCancelChecker
  -> AgentLoopOrchestrator
  -> LLM / Tool Calling
```

从产品和工程角度看，这次改造的核心价值是：TEXT 请求进入 Agent 主循环前，系统可以统一知道“这是谁的请求、属于哪个会话、识别到了什么意图、应该暴露哪些工具、是否有车辆/记忆/时间/Prompt 等上下文可观测信息”。这让后续继续做上下文压缩、工具白名单、动态 Prompt、个性化记忆、Trace 诊断时，有了一个稳定的工程落点。

当前默认模式是 `HYBRID_EXTRA_CONTEXT`：只把运行时信息、意图信息、工具组信息等轻量上下文注入到模型首轮消息中；记忆、车辆状态、时间、Prompt 等已经由旧链路负责注入或管理的内容，本阶段先只纳入 `ContextFrame` 观测，不重复塞给模型，避免同一类信息多路注入导致模型输入混乱。

## 二、我们实现了怎样的 Context 模块

### 2.1 模块定位

本阶段的 Context 模块承担四个角色：

1. **统一上下文快照**

   通过 `ContextFrame` 把一次请求相关的上下文统一保存下来，包括 requestId、clientMessageId、userId、sessionId、personaId、inputType、用户输入、意图识别结果、工具组选择结果、可渲染上下文、token 估算、Provider 调试信息等。

2. **统一上下文采集**

   通过 `ContextProvider` 抽象，把不同来源的信息拆成独立 Provider。每个 Provider 只负责一类上下文，最终由 `ContextOrchestrator` 串联执行。

3. **统一上下文注入**

   通过 `ContextExtraPreProcessor` 把 `ContextOrchestrator` 生成的 `context_rendered_extra` 注入到首轮模型请求中。这样 Context 模块不直接侵入 `AgentLoopOrchestrator` 的主流程，而是复用现有 PreProcessor 扩展点。

4. **统一上下文观测**

   通过 `ContextTraceRecorder` 将 Context 构建过程、Provider 数量、工具选择、token 估算、fallback 情况等写入 root trace，便于后续在 Phoenix 或日志系统中排查问题。

### 2.2 Context 模式

当前定义了三种运行模式：

| 模式 | 当前含义 | 本阶段状态 |
|------|----------|------------|
| `OBSERVE_ONLY` | 只构建 `ContextFrame` 和 Trace，不把 context 注入模型 | 可用于灰度观测 |
| `HYBRID_EXTRA_CONTEXT` | 默认模式，只注入轻量上下文 | 已接入生产 TEXT 链路 |
| `FULL_CONTEXT` | 完整 Context 接管模式 | 一期降级为 HYBRID 行为，并记录 `full_context_deferred` |

这套模式的意义是让后续演进有开关：我们可以先观测，再混合注入，最后逐步迁移到完整 Context 接管，而不是一次性重写所有 Prompt、Memory、Vehicle、Time 逻辑。

### 2.3 ContextFrame 包含的核心内容

`ContextFrame` 是这次模块的核心产物，它是一次 TEXT 请求的不可变上下文快照。主要内容包括：

- 请求身份：`requestId`、`clientMessageId`
- 会话身份：`userId`、`sessionId`、`personaId`
- 输入信息：`inputType`、`rawUserInput`、`normalizedUserInput`
- 意图结果：`IntentResult`
- 工具选择：`ToolGroupSelectionResult`、`selectedGroupIds`、`selectedToolNames`
- 可观测上下文：`memorySummary`、`vehicleStateSnapshot`、`timeContext`、`promptContext`
- 模型注入文本：`renderedExtraContext`
- 预算信息：`tokenEstimate`
- 构建诊断：`ContextDebugInfo`
- 原始片段：`List<ContextSection>`

它还提供了 `toOrchestratorContext()`，负责把 Context 信息合并进原有 `AgentLoopOrchestrator` 所需的 context map。这里保留了调用方已有的 `extra_context`，不会直接覆盖，而是把调用方传入的值另存为 `caller_extra_context`，降低兼容风险。

## 三、我们具体做了什么

### 3.1 建立基础数据结构

新增了 Context 模块的基础类型：

- `ContextMode`：定义 OBSERVE / HYBRID / FULL 三种模式。
- `ContextSectionType`：定义 runtime、persona、user_input、intent、tool_group、memory、vehicle_state、time、prompt、debug 等片段类型。
- `ContextSection`：表示一个 Provider 产出的上下文片段。
- `ContextFrame` / `ContextFrameBuilder`：构建最终不可变上下文快照。
- `ContextBuildInput`：保存 Context 构建依赖，比如 `ToolGroupRegistry`、`PromptManager`、`MemoryOrchestrator`、`VehicleStatusProvider`、`TimeProvider`、`ContextBudgetManager`。
- `ContextBuildResult` / `ContextProviderResult` / `ContextDebugInfo`：统一成功、fallback、错误诊断和 Provider 元信息。

这部分工作的意义是把“上下文”从散落的 Map、Prompt、PreProcessor、Trace 字段里抽象出来，变成可以测试、可以扩展、可以观测的数据模型。

### 3.2 建立 9 个 ContextProvider

当前标准 TEXT 链路包含 9 个 Provider：

| Provider | 负责内容 | 是否默认注入模型 |
|----------|----------|------------------|
| `RuntimeContextProvider` | requestId、userId、sessionId、clientMessageId 等运行时身份 | 是 |
| `PersonaContextProvider` | 当前 persona / effectivePersonaId | 是 |
| `UserInputContextProvider` | 原始输入和标准化输入 | 否，避免重复输入 |
| `IntentContextProvider` | IntentTag、confidence、reason 等意图信息 | 是 |
| `ToolGroupContextProvider` | 选中的工具组、工具名、工具组描述 | 是 |
| `MemoryContextProvider` | 记忆归属和 memory 相关元信息 | 否，本期先观测 |
| `VehicleStateContextProvider` | 车辆状态快照 | 否，避免和车辆状态 PreProcessor 重复 |
| `TimeContextProvider` | 当前时间上下文 | 否，避免和时间 PreProcessor 重复 |
| `PromptContextProvider` | 当前 Prompt 模板信息 | 否，避免和系统 Prompt 注入重复 |

这套拆分让上下文来源更清晰：每类上下文由独立 Provider 负责，Provider 失败不会直接中断整个请求，`ContextOrchestrator` 会记录 fallback 和错误原因，然后继续执行后续 Provider。

### 3.3 建立 ContextOrchestrator

`ContextOrchestrator` 是本阶段的核心执行器，当前主要做了以下事情：

1. 按固定顺序执行 9 个 Provider。
2. 收集所有 `ContextSection`。
3. 对 renderable section 做字符预算裁剪。
4. 根据 `ContextMode` 渲染 `renderedExtraContext`。
5. 对最终汇总文本再做总预算裁剪。
6. 估算 `tokenEstimate` 并写入 `ContextFrame`。
7. 生成 `ContextDebugInfo`。
8. 写入 Context 相关 Trace 字段。
9. 出现 Provider 失败时返回 fallback 结果，而不是直接让请求失败。

这部分让上下文构建成为一个明确的、可观测的管线，而不是隐藏在多个类里的临时逻辑。

### 3.4 接入 AgentRuntime

`AgentRuntime.execute()` 已经调整为：

```text
构建 ContextFrame
  -> 检查是否已取消
  -> 将 ContextFrame 传给 AgentExecutor
  -> 映射 RuntimeResult
```

这有两个重要意义：

1. Context 是在进入模型循环前完成的，后续 `AgentLoopOrchestrator` 可以拿到完整的上下文快照。
2. Context 构建完成后、真正调用 LLM 前增加了取消检查，可以降低用户取消请求后仍继续发起模型调用的风险。

### 3.5 接入 AIAgentService

`AIAgentService` 初始化阶段创建了 `ContextOrchestrator`，并注入到 `AgentRuntime`：

- 当前模式：`HYBRID_EXTRA_CONTEXT`
- 工具组来源：`ToolGroupRegistry.defaultRegistry()`
- Prompt 来源：`PromptManager`
- Memory 来源：`MemoryOrchestrator`
- 车辆状态来源：`statusProvider.getVehicleStatus()`
- 时间来源：`SystemTimeProvider`
- 取消检查来源：`ActiveRequestRegistry`

也就是说，当前 Context 模块已经进入真实 TEXT 请求路径，而不是只停留在测试代码或旁路工具里。

### 3.6 接入模型消息注入

新增 `ContextExtraPreProcessor`，负责读取：

- `context_mode`
- `context_rendered_extra`

并在首轮迭代时注入一条 `UserMessage`。当前规则是：

- `HYBRID_EXTRA_CONTEXT`：注入
- `FULL_CONTEXT`：一期降级同 HYBRID，也注入
- `OBSERVE_ONLY`：不注入

这种设计复用了现有 `PreProcessor` 机制，对 `AgentLoopOrchestrator` 主体侵入较小。

### 3.7 建立预算管理

新增 `ContextBudgetManager`，当前采用粗粒度字符预算：

- 单个普通 section 默认 800 字符
- memory 默认 500 字符
- tool context 默认 1200 字符
- 总 rendered context 默认 3000 字符
- token 估算按约 2 字符 = 1 token 计算

当前 `ContextOrchestrator` 会对 renderable section 做裁剪，并对最终 `renderedExtraContext` 做总预算裁剪。这个版本不是最终精确 token 预算，但已经能防止额外上下文无限膨胀。

## 四、Context 发挥了什么作用

### 4.1 让 Agent 的上下文入口统一

改造前，Agent 的上下文分散在多个地方：

- Prompt 模板负责系统角色和任务说明
- Memory 模块负责会话历史和长期记忆
- PreProcessor 负责车辆状态、时间等运行时信息
- IntentRouter / ToolGroupSelector 负责意图和工具组
- Trace 负责观测

这些能力都存在，但缺少一个统一的上下文装配层。Context 模块补上了这一层，让系统可以在请求进入 LLM 前统一整理“本轮对话到底处于什么上下文”。

### 4.2 让工具选择更容易被解释

当前 `ToolGroupContextProvider` 会把本轮选中的工具组和工具名写进 Context：

- 模型侧能看到轻量工具组说明，减少“所有工具都混在一起”的上下文噪声。
- Trace 侧能看到 `selected_tool_count` 和 `selected_tool_names`，方便排查为什么某次请求看到了这些工具。
- 后续如果要做 per-request 动态工具绑定，可以以这个选择结果为基础继续推进。

### 4.3 避免重复注入上下文

本阶段没有贸然把所有上下文都塞进模型。对于 memory、vehicle、time、prompt 等已经有旧链路处理的内容，Context 模块先做观测，不重复注入。

这个取舍比较重要：短期内它降低了改造风险，避免模型同时收到两份时间、两份车辆状态、两份系统 Prompt 或两套记忆摘要。

### 4.4 增强问题排查能力

Context Trace 让我们可以回答过去不好回答的问题：

- 本轮是否启用了 Context？
- 当前 Context 是什么模式？
- 跑了几个 Provider？
- 哪些 Provider 参与了构建？
- 模型看到的 selected tool 数量是多少？
- 是否发生了 fallback？
- 构建耗时是否异常？
- Context 估算 token 是否过大？

这对后续调试“模型为什么没调用工具”“为什么工具太多”“为什么上下文没有生效”“某个 Provider 是否失败”等问题有直接价值。

## 五、Context 中设置的 Trace 字段

当前 Context 模块把以下字段写入 root trace：

| Trace 字段 | 含义 | 典型用途 |
|------------|------|----------|
| `agent.context.enabled` | 是否启用 Context 构建 | 判断本轮请求是否走了新链路 |
| `agent.context.mode` | 当前 ContextMode | 区分 OBSERVE / HYBRID / FULL |
| `agent.context.provider_count` | 配置的 Provider 数量 | 检查 Provider 链是否完整 |
| `agent.context.providers` | 实际执行过的 Provider 名称列表 | 定位哪个 Provider 参与了构建 |
| `agent.context.selected_tool_count` | 本轮选中的工具数量 | 判断是否出现工具暴露过多或过少 |
| `agent.context.selected_tool_names` | 本轮选中的工具名列表 | 排查工具选择是否符合意图 |
| `agent.context.section_count` | 生成的 ContextSection 数量 | 判断上下文片段是否完整 |
| `agent.context.token_estimate` | `renderedExtraContext` 的粗略 token 估算 | 判断额外上下文是否过长 |
| `agent.context.fallback_used` | 是否发生 Provider fallback 或 FULL 降级 | 判断构建过程是否有降级 |
| `agent.context.build_ms` | Context 构建耗时 | 判断上下文构建是否影响响应时间 |
| `agent.context.error` | 首个 Provider 错误原因 | 定位 Provider 失败原因 |

从汇报角度看，这组 Trace 字段是本阶段非常关键的产出。它让 Context 不只是“把文字拼进 Prompt”，而是成为一个可以被工程团队持续观察和调优的运行时系统。

## 六、当前阶段验证情况

本阶段已建设以下 JVM 层测试覆盖：

- `ContextFrameBuilderTest`
- `ContextBudgetManagerTest`
- `ContextOrchestratorTest`
- `ContextProviderFailureTest`
- `ContextTraceRecorderTest`
- `ToolGroupContextProviderTest`
- `ContextExtraPreProcessorTest`
- `AgentLoopOrchestratorContextInjectionTest`
- `AgentRuntimeContextTest`

这些测试主要覆盖：

- ContextFrame 基础字段透传
- Provider 链构建
- Provider 失败 fallback
- 预算裁剪和 token 估算
- HYBRID / FULL / OBSERVE 模式行为
- Context 注入 PreProcessor
- AgentRuntime 中 Context 构建与取消检查
- 工具组选择结果进入 Context
- Trace 字段写入

需要注意：JVM 测试能证明代码层行为，但不能完全替代 Android 设备侧验证。AIDL、前台 Service 生命周期、真实 LLM 请求、Phoenix Trace 展示、真实车控工具调用等仍需要通过手动验收闭环。

## 七、潜在问题与可能改进项

### 7.1 当前仍是轻量 Context，不是完整 Context 接管

本阶段默认使用 `HYBRID_EXTRA_CONTEXT`，只注入 runtime、persona、intent、toolgroup 等轻量上下文。memory、vehicle、time、prompt 目前主要用于观测，不做重复注入。

后续如果要切换到真正的 `FULL_CONTEXT`，需要进一步梳理并迁移旧的 PreProcessor 和系统 Prompt 注入逻辑，确保不会重复、不遗漏、不冲突。

### 7.2 工具描述仍然偏轻量

当前 Context 中注入的是“选中的工具组 + 工具名 + 工具组描述”，还不是完整的 LangChain4j `ToolSpecification.description` 和参数 schema。

这对当前阶段是合理取舍，因为完整工具 schema 会带来更高 token 成本，也需要更深入改造工具绑定机制。但如果后续要让模型更准确理解每个工具参数，仍需要设计完整工具描述渲染和预算策略。

### 7.3 动态工具绑定尚未完成

Context 已经能知道本轮选中了哪些工具，但 `AgentLoopOrchestrator` 的实际工具列表仍主要在 persona 构建时确定。也就是说，当前 Context 能“告诉模型本轮推荐使用哪些工具”，但还不能从底层真正限制模型只看见这些工具。

后续建议推进 per-request tool specification 机制，让 Context 的工具选择结果真正影响模型可见工具集合。

### 7.4 预算管理仍是粗粒度字符预算

当前 `ContextBudgetManager` 用字符数估算 token，这能解决第一阶段“上下文无限膨胀”的问题，但不是精确 token 计算。

后续可以按模型 tokenizer 或更贴近 DashScope/LangChain4j 的 token 估算方式优化，并区分中文、英文、JSON、工具 schema 等不同内容的成本。

### 7.5 Context 中的运行时元信息需要持续关注脱敏

当前 runtime section 会包含 requestId、userId、sessionId、clientMessageId 等信息。这些信息对排查和多会话隔离有帮助，但如果直接进入模型输入，需要持续评估：

- 是否存在业务 ID 泄露风险
- 是否有必要对用户 ID 做脱敏或摘要
- 是否要区分“仅 trace 可见”和“模型可见”的字段

后续建议把 Context 字段分级：模型可见、Trace 可见、内部调试可见。

### 7.6 设备侧验收仍然必要

当前阶段的 JVM 测试覆盖了大部分逻辑，但无法完全覆盖：

- AIDL 调用链路
- Launcher / 外部 App 调用行为
- 前台 Service 生命周期
- 真实模型请求消息
- Phoenix 端 Trace 展示
- 真实车控 Tool Calling
- 用户取消请求与成功响应之间的竞态

因此建议后续按 `docs/check_accept/context-module-manual-acceptance-checklist.md` 做一次设备侧验收，并把结果沉淀到 `docs/testresult` 或 `docs/check_accept`。

## 八、后续建议

建议后续按以下顺序推进：

1. **先完成真实设备验收**

   确认 TEXT 普通对话、工具调用、取消请求、Trace 展示、非 TEXT 请求非回归都符合预期。

2. **推进工具上下文从“提示”变为“约束”**

   当前 selected tools 已经进入 Context，下一步应让它真正影响模型可见工具集合。

3. **补齐完整工具描述渲染**

   在预算可控的前提下，把 tool name、description、参数 schema 统一纳入 Context 管理。

4. **统一 memory / vehicle / time / prompt 的 Context 接管边界**

   逐步把旧 PreProcessor 分散逻辑迁移到 Context 模块，但每一步都要防止重复注入。

5. **升级预算策略**

   从字符级预算逐步升级到更接近真实模型 token 的预算，并把裁剪结果写入更详细的诊断字段。

6. **完善字段分级和脱敏策略**

   明确哪些字段可以进模型，哪些字段只进 Trace，哪些字段只保留在内部对象中。

## 九、阶段价值总结

这次 Context 模块引入，标志着 AIAgent 从“Prompt + Memory + Tool + Trace 各自分散工作”的阶段，开始进入“统一上下文工程”的阶段。

短期看，它已经解决了三个问题：

- TEXT 请求进入 LLM 前有统一上下文快照。
- 意图和工具组选择可以被模型看到，也可以被 Trace 观测。
- Context 构建过程有 fallback、预算、耗时和错误诊断。

长期看，它为后续能力打下了基础：

- 动态工具白名单
- 完整工具描述渲染
- 多用户 / 多会话上下文隔离
- 记忆压缩和上下文预算
- Prompt 选择和 Context 装配统一管理
- 端到端可观测的 Agent 输入构建链路

从项目管理视角看，本阶段不是一个简单功能点，而是一次 Agent 核心链路的架构性增强。它把“上下文”从隐式、分散、难排查的状态，推进到了显式、集中、可测试、可观测的状态。后续只要继续沿着这个模块收拢 Prompt、Memory、Tool、Vehicle、Time 等输入来源，AIAgent 的可维护性和智能表现都会有更稳定的工程基础。
