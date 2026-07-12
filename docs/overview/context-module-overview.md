# Context 模块现状与完成度审计

**审计日期：** 2026-07-12

**审计对象：** 当前工作树（包含尚未提交的 Context / Trace 修改）

**验证结果：** `testDebugUnitTest --rerun-tasks` 276/276 通过；`assembleDebug --rerun-tasks` 成功
**审计结论：** Context 已基本取得 **TEXT 主链路的模型输入控制权**，但尚未完成原始 Full Control 计划中的预算恢复、生产压缩、遗留清理和最终设备 Trace 验收。当前适合作为 Demo 主链路继续迭代，不应描述为“全部开发完成”。

---

## 一、模块定位

Context 是 TEXT Agent 的**模型输入控制层**。它不替代 Prompt、Memory、Tool、Vehicle 等能力模块，而是统一调用这些模块，通过 Provider 获取上下文，再生成每轮 LLM 实际接收的：

- `List<ChatMessage>`
- `List<ToolSpecification>`
- Token 预算报告
- Provider 与装配诊断信息

职责边界如下：

| 能力 | Context 负责 | 原能力模块负责 |
|---|---|---|
| Prompt | 决定是否进入模型、放在哪个消息区域 | `PromptManager` 加载和渲染模板 |
| Session Memory | 每轮读取并装入消息 | Memory 存储、ChatMemory 生命周期和持久化 |
| Long-term Memory | 按用户读取并装入 Context Data | Memory 存储、提取和更新 |
| Tool | 决定本轮暴露哪些 ToolSpecification | ToolGroup 选择，ToolRegistry 解析，ToolExecutor 执行 |
| Vehicle / Time | 决定是否进入模型以及装配顺序 | 各模块提供真实状态和时间 |
| Compression | 决定何时需要压缩及目标预算（目标设计） | Memory 实现压缩算法与一致性写回 |

因此，Context 的合理目标不是“吞并其他模块”，而是成为模型输入的唯一控制平面。

---

## 二、当前生产调用链

```text
AIAgentService.handleTextRequest()
  -> AgentRuntime.startSession()
       -> IntentRouter
       -> ToolGroupSelector
       -> RequestSession（含 resolved sessionId / userId / personaId）
  -> AgentRuntime.execute()
       -> ContextOrchestrator.prepare()
            -> REQUEST_STATIC Providers
            -> ContextFrame
       -> TextAgentLoopOrchestrator.execute()
            -> 每轮 ContextOrchestrator.assemble()
                 -> ITERATION_DYNAMIC Providers
                 -> ContextMessageAssembler
                 -> ContextAssemblyResult
            -> ChatRequest.builder()
                 .messages(assemblyResult.messages())
                 .toolSpecifications(assemblyResult.toolSpecifications())
            -> ModelCaller
            -> Tool/Safety/Memory 写回
```

代码审查确认：TEXT 生产路径构造 `ChatRequest` 时，消息和工具规格均直接取自 `ContextAssemblyResult`。`TextAgentLoopOrchestrator` 不再自行渲染 System Prompt，也不再自行拼接模型输入消息。

该结论只适用于 TEXT 主链路。旧 `AgentLoopOrchestrator` 及 SCENE/VL 等非 TEXT 路径仍保留，不属于当前 Context 唯一控制范围。

---

## 三、内部架构

### 3.1 两阶段生命周期

#### Request Static：每个请求执行一次

| 顺序 | Provider | required 语义 | 当前用途 |
|---|---|---|---|
| 1 | `RuntimeContextProvider` | 必需 | 运行身份元数据，`POLICY_ONLY` |
| 2 | `PersonaContextProvider` | 可选 | Persona 元数据，`POLICY_ONLY` |
| 3 | `PromptContextProvider` | 必需 | 唯一 System Prompt |
| 4 | `UserInputContextProvider` | 必需 | 唯一 CURRENT_USER 消息 |
| 5 | `IntentContextProvider` | 可选 | 意图元数据，`POLICY_ONLY` |
| 6 | `ToolGroupContextProvider` | 非 CHAT_ONLY 时必需 | 真实工具规格或无工具集合 |
| 7 | `LongTermMemoryContextProvider` | 可选 | 用户级长期记忆 |
| 8 | `CallerExtraContextProvider` | 可选 | 外部调用方附加上下文 |

#### Iteration Dynamic：每轮模型调用前执行

| 顺序 | Provider | required 语义 | 当前用途 |
|---|---|---|---|
| 1 | `SessionMemoryContextProvider` | 必需 | 当前 Session 历史消息快照 |
| 2 | `VehicleStateContextProvider` | 工具组要求车辆状态时必需 | 每轮车辆状态 |
| 3 | `TimeContextProvider` | 可选 | 每轮时间上下文 |

这种拆分是合理的：Prompt、用户输入、工具选择等请求不变量只准备一次；SessionMemory、车辆状态和时间会随工具循环变化，因此每轮重新读取。

### 3.2 Contribution 契约

Provider 统一输出 `ContextContribution`，具体分为：

| 类型 | 载荷 | 用途 |
|---|---|---|
| `TextContextContribution` | 文本、targetArea、可见性、信任级别 | System Prompt 或 Context Data |
| `MessageContextContribution` | `List<ChatMessage>`、messageSource | SessionMemory 或当前用户消息 |
| `ToolContextContribution` | `List<ToolSpecification>`、selectionMode | 本轮工具集合 |

关键控制字段包括：

- `ContextVisibility`：`MODEL_VISIBLE`、`POLICY_ONLY`、`TRACE_ONLY`
- `ContextTrustLevel`：`TRUSTED_SYSTEM`、`TRUSTED_DATA`、`UNTRUSTED_DATA`
- `ContextPriority`：`CRITICAL`、`HIGH`、`NORMAL`、`OPTIONAL`、`TRACE_ONLY`
- `ContextLifecycle`：`REQUEST_STATIC`、`ITERATION_DYNAMIC`

当前优先级主要是数据契约，尚未真正驱动生产裁剪。

### 3.3 消息装配规则

`ContextMessageAssembler` 当前按固定顺序生成消息：

```text
iteration = 0
1. 唯一 SystemMessage
2. 可选 Context Data UserMessage
3. SessionMemory 消息序列
4. 唯一 CURRENT_USER UserMessage

iteration > 0
1. 唯一 SystemMessage
2. 可选 Context Data UserMessage
3. SessionMemory 消息序列（已包含当前用户、工具请求和工具结果）
```

当前用户消息在 iteration 0 装配成功且预算/取消检查通过后才写入 ChatMemory，避免失败请求污染历史。后续迭代不再重复追加静态 CURRENT_USER，而是从 SessionMemory 动态快照读取。

`ContextMessageSequenceValidator` 会检查：

- SystemMessage 唯一且位于首位
- Tool Call 与 Tool Result 闭合
- pending Tool Call 未闭合时不能进入新的 User/Ai 消息

Context Data 通过 `ContextDataFormatter` 包装，并标记来源与信任等级，避免长期记忆、车辆状态或 caller extra 被误当成当前用户指令。

### 3.4 工具上下文

工具规格已由 `ToolGroupContextProvider` 统一进入 Context：

- `CHAT_ONLY`：空工具集合
- 明确命中 ToolGroup：解析选中工具规格
- 上游明确 `allToolsFallback`：返回 ToolRegistry 的全量可用工具
- 规格解析失败：返回稳定失败，不静默扩大为全量工具

Assembler 按工具名去重；同名但 schema 不同会返回 `MESSAGE_SEQUENCE_INVALID`。

### 3.5 预算

当前 Demo Profile：

| 参数 | 值 |
|---|---:|
| 模型窗口 | 32768 |
| 输出预留 | 2048 |
| 安全余量 | 1024 |
| 最大输入预算 | 29696 |

`HeuristicContextTokenEstimator` 对消息正文、消息结构开销和工具 schema 做低成本估算。若估算超限，AgentLoop 返回 `CONTEXT_BUDGET_EXCEEDED`，不会调用模型。

当前只实现了**预算检测和硬失败**，没有完成自动裁剪、生产压缩和重新装配。

---

## 四、开发完成度

### 4.1 分能力评估

| 能力 | 完成度 | 审计结论 |
|---|---|---|
| TEXT ChatMessage 唯一生成权 | 95% | 生产 TEXT `ChatRequest.messages` 只来自 Context；旧非 TEXT 路径仍存在 |
| TEXT ToolSpecification 唯一生成权 | 95% | 生产 TEXT 只使用 Context 输出；工具选择和解析仍由原模块实现，边界合理 |
| Provider 真实接入 | 85% | Prompt、Memory、Tool、Vehicle、Time、Caller Extra 均有真实来源；部分 Provider 状态语义仍有缺陷 |
| 消息顺序与 ToolExchange 校验 | 85% | 已有严格校验；SessionMemory 固定条数截取可能先切断交换，再被校验拒绝 |
| Session/User/Persona 隔离 | 85% | sessionId 控制短期历史，userId 控制长期记忆，persona 控制 Prompt；已具备测试但仍需设备长会话验证 |
| Context Data 指令隔离 | 90% | 已有独立 envelope、disclaimer、source/trust 标记和边界转义 |
| 预算估算与拦截 | 70% | 覆盖消息与工具 schema，但只是启发式估算和硬失败 |
| 优先级裁剪 | 10% | 类型和旧算法存在，生产装配未使用 |
| 生产自动压缩 | 15% | Memory 有 plan/execute 接口，Context/AgentLoop 未触发生产恢复流程 |
| Context Trace 树 | 75% | prepare/assemble/provider/fragment/message/toolset span 已接入并通过 JVM 测试，但仍存在准确性和完整内容问题 |
| Legacy 清理 | 40% | FULL/HYBRID 模式主体已退出 TEXT，但旧类型、旧预算 API、旧 Trace API 和非 TEXT AgentLoop 仍保留 |
| 设备/Phoenix 最终验收 | 40% | 用户已验证设备基本对话可运行；本轮新增 Trace 结构尚无 Phoenix 实机证据 |

### 4.2 总体判断

- **以“TEXT 模型输入统一接管”为目标：约 90% 完成。** 核心控制权已经迁移成功。
- **以原始 Full Control 完整计划为目标：约 70% 完成。** 主要缺口是裁剪、压缩恢复、遗留清理和最终 Trace 验收。
- **以可长期维护的生产级实现为目标：约 65% 完成。** 当前更接近可运行 Demo，而不是完整生产级 Context 平台。

这些百分比是基于能力项的工程判断，不是代码行数统计。

---

## 五、已确认问题与风险

### P1：SessionMemory 固定 50 条截取可能破坏 ToolExchange 原子性

`SessionMemoryContextProvider` 固定请求最近 50 条消息；`MemoryOrchestrator.sessionMemorySnapshot()` 直接使用 `subList(size - max, size)`。如果截取边界落在 `AiMessage(tool calls)` 与 `ToolExecutionResultMessage` 之间，Assembler 会收到不完整工具交换，随后被序列校验拒绝。

**影响：** 长会话可能突然返回 `MESSAGE_SEQUENCE_INVALID`，即使数据库中的完整历史本来合法。
**建议：** Memory 提供按完整 ConversationTurn / ToolExchange 边界裁剪的快照，或由 Context 的结构化预算裁剪统一处理；禁止按裸消息条数直接切片。

### P1：超预算没有恢复路径

当前超预算后直接失败：

- 不按 `ContextPriority` 删除 optional contribution
- 不按完整 turn 裁剪旧 SessionMemory
- 不调用 `planSessionCompaction()` / `executeCompactionPlan()`
- 不执行压缩后的二次装配

**影响：** 对话增长到阈值后会从“正常工作”直接变成“无法对话”。
**建议：** 实现单次请求最多一次的恢复流程：装配 -> 超限 -> 安全裁剪 -> 必要时请求 Memory 压缩 -> 重新读取快照 -> 二次装配 -> 仍超限才硬失败。

### P1：当前 Trace 尚未满足“完整、无截断地看到模型输入”

虽然已新增 Provider、fragment、message、toolset span，但当前仍有以下缺口：

- `recordMessage()` 只记录第一条消息的 200 字符摘要，并明确追加 `truncated`
- `recordToolset()` 只记录工具名，不记录完整 description/parameters schema
- 旧 `context.assembled_messages` / `context.assembled_tool_specs` 已停止默认写入
- 内容最终还经过 `TraceAttributeWriter`，是否完整取决于现有 Trace 配置/写入策略
- 尚未在 Phoenix 真机确认最终显示效果

**影响：** 仍不能仅靠 Context Trace 还原某轮模型看到的完整消息和工具 schema。
**建议：** 按 Demo 已确认边界，默认记录每轮最终 `ChatRequest` 的完整 messages 和 toolSpecifications，不做应用层截断；同时保留 Provider 来源和是否入模信息。

### P2：Provider span 没有包裹真实 Provider 执行

`ContextOrchestrator` 当前先调用 `provider.provide()`，调用结束后才创建 `context.provider.<name>` span。`provider.duration_ms` 虽然用外部计时写入，但 span 自身并不覆盖数据库读取、Prompt 渲染或 ToolRegistry 解析的真实执行区间；异常 span 也只在异常发生后创建。

**影响：** Trace 树看似有 Provider span，但时间线和异常归属不准确，Provider 内部子 span 也无法挂载。
**建议：** 在 `provider.provide()` 之前创建并 `makeCurrent()`，在 finally 中结束；状态、异常和 duration 在同一个真实执行 span 上记录。

### P2：LongTermMemory Provider 吞掉了降级状态

`LongTermMemoryContextProvider` 内部计算了 `FALLBACK` 和 `errorDetail`，但方法结尾无条件返回 `ContextProviderResult.success(...)`。因此 memory 未配置或读取异常时，prepare outcome 和 Trace 都会被错误记录为成功。

**影响：** 长期记忆缺失会静默发生，排障时误判为“Provider 正常且没有记忆”。
**建议：** 异常/未配置时返回 `fallback(...)`；正常空记忆仍返回 success + 空 contribution。

### P2：ContextFrame 仍不是纯请求级最小快照

`ContextFrame` 继续保留 `sections`、`debugInfo`、`tokenEstimate`、`renderedExtraContext`、`memorySummary`、`vehicleStateSnapshot`、`timeContext`、`promptContext` 等迁移字段。当前生产装配主要依赖 `contributions`，这些字段容易制造第二份事实来源。

**影响：** 后续维护者可能误用旧字段，重新引入双链路或状态不一致。
**建议：** 非 TEXT 路径删除或冻结后，一次性清除旧字段及对应 Builder/API；在此之前明确标注 deprecated，禁止新代码使用。

### P2：ContextPolicy 仍是分散规则，不是独立策略层

原始计划提出 `ContextPolicy / DefaultContextPolicy`，当前 required、visibility、priority 主要写在各 Provider 内。Demo 阶段可以运行，但规则分散在 Provider、ToolGroup Registry 和 Assembler 中。

**影响：** 新增来源后，是否入模、何时必需、如何裁剪容易出现不一致。
**建议：** 不急于新增复杂框架；等实现裁剪时再提取最小策略对象，统一管理 required、visibility 和 drop order。

### P2：启发式 Token 估算存在模型偏差

当前估算采用字符数启发式和 1.15 安全系数，不是 Qwen tokenizer 的精确值。中文、JSON、工具 schema 和特殊消息结构的偏差可能不同。

**影响：** 可能提前拒绝，也可能低估后被模型 API 拒绝。
**建议：** Demo 阶段继续使用低成本估算，但记录 estimated tokens 与模型返回 usage 的差值，基于设备样本校准系数。

### P3：ContextOrchestrator 仍保留过时注释和兼容 API

例如 assemble JavaDoc 仍写有“Phase 2 仅建立骨架”，实际早已进入生产；`recordProviderEvent()`、旧 root attribute API、旧 Budget 类型等仍存在。

**影响：** 文档与代码认知成本上升，但不直接影响运行。
**建议：** 功能稳定后集中清理，不与预算/压缩功能改造混在同一提交。

### P3：PostProcessor 输出与 SessionMemory 中的 AiMessage 可能不一致

AgentLoop 在 PostProcessor 之前已把模型原始 `AiMessage` 写入 ChatMemory；最终返回给用户的是处理后的 `output`。如果 PostProcessor 改写正文，下一轮历史看到的是原始回答而不是用户实际收到的回答。

**影响：** 对话历史可能与外部用户体验不一致，间接降低后续回答质量。
**建议：** 明确 Memory 要保存原始模型输出还是最终用户可见输出；若目标是会话连续性，应保存最终可见文本或同时区分 raw/final。

---

## 六、测试与验证现状

### 6.1 本轮实际执行

- `testDebugUnitTest --rerun-tasks`：276 tests，0 failures，0 errors，0 skipped
- `assembleDebug --rerun-tasks`：成功
- 构建存在 AndroidManifest 重复 permission 警告，与本次 Context 审计无直接关系

### 6.2 已覆盖的主要场景

- Provider required/optional/fallback 行为
- System、Context Data、SessionMemory、CURRENT_USER 装配顺序
- iteration 0 与后续 iteration 的当前用户去重
- Tool schema 冲突和 ToolExchange 序列校验
- Context 预算超限拦截
- Session、User、Persona、ToolGroup 端到端语义
- Context prepare/assemble/provider/fragment/message/toolset Trace 层级
- Debug APK 编译和打包

### 6.3 测试不能证明的内容

- 真实 SQLite 长会话在 50 条截取边界处是否保持 ToolExchange 完整
- 真实 Qwen Token 与启发式估算的偏差
- 生产压缩恢复流程（当前根本未接入）
- Phoenix 中完整消息和完整工具 schema 的实际可见性
- Provider span 的真实耗时区间（当前实现并未包裹调用）
- 车机长时间运行、并发请求、取消与超时竞争下的稳定性

---

## 七、遗留类型与清理边界

当前生产 Contribution 路径已经可以不依赖以下旧概念，但代码仍有兼容引用：

- `ContextSection`
- `ContextSectionType`
- `ContextDebugInfo`
- `ContextBudgetManager`
- `ContextBudgetDecision`
- `ContextFrame` 中 legacy 字段
- `AgentLoopOrchestrator` 的旧消息装配入口
- `buildSystemPromptMessage()`
- 旧 `prompt.assembly` / Context root attribute Trace API

这些内容不是“还在控制 TEXT 输入”，而是尚未完成的迁移清理。考虑到 SCENE/VL/CONTROL 暂时保留，建议先禁止新增引用，等非 TEXT 路径明确删除后统一清理，避免在功能修复阶段扩大改动面。

---

## 八、后续改进优先级

### 第一优先级：保证长会话不会突然失效

1. 修复 SessionMemory 按裸消息条数截取的问题，保证 ToolExchange/ConversationTurn 原子性。
2. 实现预算超限恢复：优先级裁剪、必要时 Memory 压缩、二次装配。
3. 增加真实长会话回归测试，覆盖工具调用恰好跨越窗口边界。

### 第二优先级：让 Trace 真正可用于定位回答质量

1. 记录每轮最终 `ChatRequest.messages` 的完整内容。
2. 记录完整 ToolSpecification（name、description、parameters）。
3. Provider span 必须包裹真实调用。
4. 修复 LongTermMemory fallback 状态。
5. 在 Phoenix 设备侧验证同一 traceId 下的完整树和内容。

### 第三优先级：降低技术债务

1. 校准 Token 估算。
2. 明确 PostProcessor 后的最终文本如何写入 SessionMemory。
3. 删除 legacy Context 类型、旧预算 API 和过时注释。
4. 在确有裁剪需求时提取最小 `ContextPolicy`，不要提前引入复杂配置系统。

---

## 九、最终结论

Context 本轮改造的核心方向是正确的：它已经从“额外上下文观察器”变成 TEXT 模型输入的实际控制层，Prompt、Memory、Tool 等模块仍保留各自实现所有权，符合当前自研 AgentLoop + LangChain4j 底层原语的架构边界。

目前最准确的状态是：

> **TEXT 输入统一接管基本完成；长会话预算恢复、完整 Trace 和遗留清理尚未完成。**

后续不需要再次重写 Context 架构。应围绕三个明确缺口做收口：**长会话原子裁剪、超预算恢复、完整可观测性**。完成这三项后，Context 才能从“可运行 Demo”进入“稳定可维护”的阶段。
