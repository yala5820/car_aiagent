# ToolGroup 接口改进计划可行性审查

**审查对象：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md`  
**审查日期：** 2026-07-10  
**审查结论：** 方向基本可行，但不建议直接进入执行。当前计划至少有 1 个会导致编译/测试改造预期失真的接口迁移问题，另有若干边界与实现细节需要先修正。

---

## 一、总体判断

这份计划的主方向符合本轮任务目标：

- 仍把 ToolGroup 定位为候选工具选择层，不接管 LangChain4j 的实际 `toolSpecifications` 绑定。
- 明确 `CHAT` 不等于“选择不出”，普通闲聊仍走 `CHAT_ONLY_GROUP`。
- 明确 `UNKNOWN` / selector 异常 / selector 返回 null 等不可判定场景可全量兜底。
- 通过 `ToolGroupRegistry` 暴露 context key、risk、聚合组、全量候选 toolName 和 LangChain4j spec 一致性校验，方向合理。

但计划需要先修正以下问题后再实施，尤其是 `ToolGroupSelector` 接口升级方式和 Runtime registry 归属。

---

## 二、必须修正的问题

### P0：`ToolGroupSelector` 改主抽象方法会破坏现有 lambda 调用，计划低估了改造范围

计划在 Task 1.2 中要求将主接口改为：

```java
ToolGroupSelectionResult select(ToolGroupSelectionInput input)
```

并把旧的 `select(IntentResult intentResult, String userInput)` 保留为 default 方法。

这个设计会改变 Java Functional Interface 的唯一抽象方法签名。当前代码和测试中存在二参 lambda，例如：

- `AgentRuntimeTest` 中使用 `(intentResult, userInput) -> ...`
- `AgentRuntimeToolGroupTraceTest` 中使用 `(intentResult, userInput) -> ...`

一旦主抽象方法改成单参 input，这些二参 lambda 不会继续匹配接口，必须改写。计划里“保留现有测试中旧签名的调用，证明兼容路径未断”的表述不成立。

**影响：**

- 直接执行计划会出现编译错误或测试改造遗漏。
- 计划声称“旧测试不需要大规模重写”，但实际至少所有自定义 selector lambda 都要改。
- 如果外部模块也用 lambda 实现 `ToolGroupSelector`，也会被破坏。

**建议修正：**

二选一：

1. 保持当前二参方法为唯一抽象方法，新增 default `select(ToolGroupSelectionInput input)`，由它拆出 `intentResult/userInput` 后委托旧方法。这样兼容性最好，但未来 selector 无法强制消费扩展字段。
2. 接受 SAM 签名变更，但计划必须明确列出所有 lambda 调用点改写，并删除“旧签名调用不需要重写”的验收标准。

如果本轮目标是“初级接口预留、低风险改造”，更建议选方案 1。

---

### P1：Runtime fallback registry 的“同一份 registry”目标没有给出可执行 wiring

计划要求：

- `AgentRuntime` 内部保存 `ToolGroupRegistry` 字段，用于 selector 异常 / null 返回时构造全量 fallback。
- 默认构造路径只创建一次 `ToolGroupRegistry.defaultRegistry()`，同一个 registry 同时用于 `DefaultToolGroupSelector` 和 Runtime fallback。

但当前代码结构里 registry 分散创建：

- `AgentRuntime.defaultContextOrchestrator()` 内部创建一份 `ToolGroupRegistry.defaultRegistry()`。
- 多个 `AgentRuntime` 构造函数分别创建 `new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry())`。
- `AIAgentService` 初始化 `ContextOrchestrator` 时也创建一份 `ToolGroupRegistry.defaultRegistry()`。

计划没有说明如何在不新增 public 构造函数的情况下，把同一份 registry 同时传给 selector、Runtime fallback，以及可选的 ContextOrchestrator。只说“只创建一次”不足以指导实现。

**影响：**

- 执行者可能只给 Runtime 新增 registry 字段，但 selector 和 ContextBuildInput 仍使用其他 registry 实例。
- 当前 registry 是不可变默认注册表时，多个实例行为一致，问题不大；但计划既然把“同一份 registry”作为目标，就需要明确实现路径。

**建议修正：**

计划中补充一个明确策略：

- 若只要求“语义一致”，把“同一份 registry”改成“同一套默认 registry 定义”，不强求对象同实例。
- 若确实要求同实例，新增 private 构造汇聚方法或 private holder，确保默认 selector 与 Runtime fallback 共用同一个局部变量创建出的 registry。
- `AIAgentService` 注入的 `ContextOrchestrator` 已经外部构造，是否也必须共享同一 registry，需要明确边界；否则不要在计划中承诺 Context 与 Runtime 同实例。

---

### P1：`ToolGroupSelectionResult` 新增派生字段后，旧 `of(...)` 默认值会制造“不完整候选结果”

计划要求在 `ToolGroupSelectionResult` 中新增：

- `requiredContextKeys`
- `highestRiskLevel`
- `allToolsFallback`
- `containsAggregationGroup`

同时保留旧 `of(...)`，并默认填空 context keys、`LOW` 风险、非全量兜底。

这会产生一个隐患：调用方如果继续使用旧 `of(...)` 创建 `AC_GROUP + BASIC_STATUS_GROUP`，结果里的 `requiredContextKeys` 会是空，`highestRiskLevel` 会是 `LOW`，与 registry 中真实语义不一致。当前测试和自定义 selector 正在使用 `ToolGroupSelectionResult.of(...)`，所以这是现实风险。

**影响：**

- 外部模块可能读取 `result.requiredContextKeys()`，但拿到的是旧工厂默认空值。
- `highestRiskLevel()` 可能低估高风险组。
- 后续 Context 模块若直接信任 result，会遗漏上下文需求。

**建议修正：**

计划必须明确不变式：

- 生产路径所有选择结果必须通过 `DefaultToolGroupSelector` 的统一构建方法，由 registry 填充派生字段。
- 旧 `of(...)` 仅作为测试/兼容轻量工厂，文档标注“不保证派生字段完整”。

更稳妥的做法是新增一个命名清晰的工厂，例如：

```java
ToolGroupSelectionResult.candidate(...)
ToolGroupSelectionResult.enriched(...)
ToolGroupSelectionResult.allToolsFallback(...)
```

并逐步减少生产代码对旧 `of(...)` 的依赖。

---

### P1：计划修改 `ToolGroupContextProvider`，需要确认是否越过“本轮只预留接口”的边界

计划把 `ToolGroupContextProvider` 的全量兜底渲染改为摘要模式，这个改动本身合理，可以避免 47 个 toolName 注入首轮模型上下文。

但本轮任务目标是“规范 ToolGroup 模块的候选工具包、候选工具名、上下文需求和兜底语义，为后续 Context 模块统一管理工具上下文预留稳定接口”。如果严格按“ToolGroup 管理逻辑 + 预留接口”执行，修改 `context/provider/ToolGroupContextProvider.java` 已经进入 Context 渲染行为。

**影响：**

- 如果你希望本轮只改 ToolGroup 包和 Runtime 防御路径，这个改动应后移。
- 如果接受“ToolGroupContextProvider 属于 ToolGroup 信息展示边界”，则需要在计划开头明确这是本轮唯一允许的 Context 侧轻量适配，而不是 Context 架构改造。

**建议修正：**

请在计划中明确二选一：

1. 本轮允许修改 `ToolGroupContextProvider`，但仅限 `allToolsFallback` 摘要渲染，不改 `ContextExtraPreProcessor`、不新增 provider、不改 context build 流程。
2. 本轮完全不动 context 包，只在 `ToolGroupSelectionResult` 提供 `allToolsFallback` 和候选摘要字段，ContextProvider 改动放到后续 Context 阶段。

---

## 三、建议补充的问题

### P2：`highestRiskLevel` 不宜长期使用裸字符串

当前 `ToolGroup.riskLevel()` 是字符串，计划继续用 `String highestRiskLevel`。短期可行，但既然本轮要规范接口，建议至少增加集中常量或内部排序方法，避免 `"HIGH"` / `"High"` / `"高"` 这类拼写差异进入稳定接口。

**建议：**

- 轻量方案：新增 `ToolGroupRiskLevels` 常量类或 registry 内私有排序函数。
- 更规范方案：新增 `ToolGroupRiskLevel` enum，但这会扩大改动范围，需要权衡。

本轮如果追求最小改动，可以先用字符串，但计划必须写清楚合法值只有 `LOW/MEDIUM/HIGH`。

---

### P2：`allToolNames()` 和一致性校验需要明确是否忽略 disabled group

当前 `toolNamesFor(...)` 会跳过 `enabled == false` 的组。计划新增 `allToolNames()`、`validateAgainstToolSpecifications(...)`，但没有说明是否只统计 enabled group。

**建议：**

- `allToolNames()` 应明确只统计 enabled group，还是统计全部注册 group。
- 如果未来 disabled group 表示临时下线，校验时是否仍要求其 toolName 存在，也需要明确。
- 当前所有 group 都 enabled，所以短期测试可能看不出这个问题，但接口语义应提前写清楚。

---

### P2：测试中构造 `ToolSpecification` 的方式不够具体

计划写到“测试使用最小可构造的 `ToolSpecification` 列表”，但没有给出具体构造方式。考虑到 LangChain4j 的 `ToolSpecification` 构造 API 可能比较固定，计划应给出准确代码片段或复用现有 `ToolRegistry.getToolSpecifications()` 的测试策略。

**建议：**

- 在计划中补充 `ToolSpecification.builder().name(...).description(...).build()` 这类可编译示例；如果当前版本 API 不支持这种最小构造，则改为从测试用 `@Tool` manager 反射生成 specs。
- 避免执行阶段才发现测试无法构造 spec。

---

### P2：全量兜底摘要中不要硬编码 47

计划要求摘要固定输出：

```text
- selectedToolCount: 47
```

这与当前工具数量一致，但后续新增工具后会变成文档/测试脆弱点。

**建议：**

- 生产代码使用 `toolNames.size()` 动态输出。
- 测试断言使用 `ToolGroupRegistry.defaultRegistry().toolNamesFor(List.of(ALL_SAFE_DEMO_GROUP)).size()` 或新 `allToolNames().size()`，不要硬编码 47。
- 文档中可以说“当前为 47 个”，但接口和测试不要依赖固定数量。

---

### P2：全量兜底的 trace 可观测性略弱

计划要求不新增 trace 字段，只继续写入 selected group/tool/reason。这样能保持改动小，但 `allToolsFallback` 这个新语义只能靠 `selectionReason` 或 `ALL_SAFE_DEMO_GROUP` 间接判断。

**建议：**

- 如果坚持不新增 trace 字段，必须统一 reason 命名，确保包含 `_all_tools`。
- 如果允许轻量增强，可增加一个 `agent.tool_group.all_tools_fallback` trace attribute。该项不是本轮必须，但会提升排查效率。

---

## 四、对阶段划分的评价

阶段划分总体合理：

1. 先固定输入/输出契约。
2. 再补 registry 查询和一致性校验。
3. 再调整 selector / Runtime fallback。
4. 最后更新文档和记录测试结果。

但建议调整两个顺序细节：

- `ToolGroupRegistry` 的查询能力应先于 `ToolGroupSelectionResult` 派生字段落地，因为 result 的 context key / risk / aggregation 都依赖 registry 计算。
- `ToolGroupContextProvider` 是否放入阶段三，应先由你确认边界。如果本轮只做 ToolGroup 接口预留，建议移出本计划。

---

## 五、建议修订后的最小执行边界

如果要保持本轮风险最低，建议把实施范围收敛为：

- 新增 `ToolGroupSelectionInput`，但不要急于改变 `ToolGroupSelector` 的 SAM 抽象方法，避免破坏现有二参 lambda。
- 增强 `ToolGroupRegistry` 查询接口：全量 toolName、context keys、risk、aggregation、spec 校验。
- 增强 `ToolGroupSelectionResult`，但明确旧 `of(...)` 是兼容工厂，生产选择结果必须经 registry enriched。
- `DefaultToolGroupSelector` 保持简单规则，仅调整 `UNKNOWN` / null intent 的全量兜底语义。
- `AgentRuntime` 只处理 selector null/exception 的全量兜底，不碰 AgentLoop binding。
- `RequestSessionFactory` 保持轻量 null 防线。
- `ToolGroupContextProvider` 是否修改，作为单独可选项等待确认。

---

## 六、最终结论

这份计划不是方向性错误，核心目标和架构边界基本正确。但它还不是可以直接交给执行者逐项实施的版本。

实施前至少需要修正：

1. `ToolGroupSelector` SAM 签名变更导致的 lambda 兼容问题。
2. Runtime / selector / context registry 是否必须同实例的 wiring 说明。
3. `ToolGroupSelectionResult` 派生字段的完整性不变式。
4. `ToolGroupContextProvider` 是否属于本轮允许修改范围。

修正这些点后，本计划可以作为初级实现计划继续细化。
