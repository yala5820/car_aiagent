# ToolGroup Interface Improvement 修改后复验报告

**复验日期：** 2026-07-10  
**复验基线：** `docs/review/toolgroup/2026-07-10-toolgroup-interface-improvement-acceptance-review.md`  
**复验范围：** ToolGroup Phase 1-4 当前生产代码、Runtime/Context 边界、单元测试、overview、工作总结和测试结果文档  
**任务边界：** 本阶段只规范候选工具组、候选 toolName、上下文需求和兜底接口；不接入 AgentLoop 动态工具绑定，不要求 Runtime 使用单参 selector，不实现小 LLM / 子 agent selector  
**复验结论：** **不通过，但上一轮主要问题已基本修复；当前剩余 1 个 P1、4 个 P2**

---

## 一、总体判断

下属已经完成上一版报告中大部分实质修复：

- `DefaultToolGroupSelector` 已改用 Registry-enriched 结果工厂。
- Runtime 在 Registry 为 null 时不再伪造全量兜底结果。
- `allToolNames()` 已改为从 enabled、非聚合、非上下文标记组动态合并。
- `ToolGroupContextProvider` 已把全量摘要判断移到 Registry null 判断之前。
- `ToolGroupSelectionInput.Builder` 的 persona 默认值已统一为 `chat`。
- `ToolGroup.isContextMarker()` 和 `ToolGroup.isAggregation()` 已实现，Registry 已委托实例方法。
- selector 返回 null 和 `RequestSessionFactory` 收到 null selection 的测试已补充。
- overview 已修正二参 SAM / 单参 default 方法的当前事实。
- 新增了一条基于真实 `VehicleAcManager` 的 `ToolSpecification` 测试。

当前默认生产路径能够正确编译、测试和构建；源码扫描也确认当前 47 个真实 `@Tool` 名称与 Registry 基础列表一致。

但本轮核心目标包括“规范全量兜底语义”。当前 `allToolsFallback(...)` 仍由调用方传入任意列表并硬编码派生元信息，无法保证 `allToolsFallback=true` 一定代表 Registry 当前全部 enabled 工具。该问题直接影响未来 Context 对兜底结果的可信度，因此保留为 P1。

---

## 二、上一版问题复验结果

| 上一版问题 | 当前状态 | 复验结论 |
|------------|----------|----------|
| 完整候选结果可出现矛盾派生语义 | 新增 `enriched(...)`，DefaultSelector 已使用；但全量兜底仍绕过 Registry-enriched 构造 | 部分修复，保留 1 个 P1 |
| Runtime null-Registry 伪全量 | 已改为轻量 `fallback(...)` | 已修复 |
| `allToolNames()` 未实现 enabled 语义 | 已遍历 enabled 非聚合业务组 | 代码已修复，测试仍缺失 |
| ContextProvider null-Registry 时展开全量名称 | 分支顺序已修正 | 代码已修复，测试仍缺失 |
| Builder persona 默认值错误 | 已默认 `chat`，测试已同步 | 已修复 |
| `ToolGroup` 实例语义缺失 | 已新增两个实例方法并由 Registry 委托 | 已修复 |
| 真实 ToolSpecification 回归缺失 | 新增 AC Manager 真实规格测试 | 部分修复，只覆盖 15/47 个工具 |
| selector null / Factory null selection 测试缺失 | 两项均已补充 | 已修复 |
| Trace fallback、null Registry、disabled group 等测试缺失 | 仍未补充 | 未完全修复 |
| overview / summary / testresult 事实偏差 | overview 部分修正，summary 和 testresult 仍是旧内容 | 部分修复 |

---

## 三、剩余问题

### P1-1：`allToolsFallback=true` 仍不能保证候选列表是 Registry 当前全部工具

**证据：**

- `ToolGroupSelectionResult.java:140-151` 的工厂仍是：

```java
allToolsFallback(List<String> allToolNames, String reason)
```

- 工厂允许调用方传入任意列表或 null；null 会被转换为空列表，但结果仍设置：
  - `selectedGroupIds=[ALL_SAFE_DEMO_GROUP]`
  - `fallbackUsed=true`
  - `allToolsFallback=true`
  - `highestRiskLevel=HIGH`
  - `containsAggregationGroup=true`
- `ToolGroupRegistryTest.java:175-185` 明确用 `tool_a/tool_b/tool_c` 三个任意名称构造“全量兜底”，测试仍判定该结果合法。
- `ToolGroupSelectionResult.java:110-114` 声明 DefaultSelector、AgentRuntime 生产路径只使用 `enriched(...)`，但 `AgentRuntime.java:267-275` 实际仍调用 list-based `allToolsFallback(...)`。
- DefaultSelector 的无法判定路径通过 `enriched([ALL_SAFE_DEMO_GROUP])` 读取聚合组快照；Runtime selector null/exception 路径通过 `registry.allToolNames()` 读取 enabled 业务组集合。当前默认数据相同，但两个全量兜底数据源并未统一。

**根因：**

上一轮修复新增了 Registry-enriched 普通结果工厂，但保留了旧的 list-based 全量兜底工厂。全量候选集合、风险、上下文需求和聚合标记仍在 `ToolGroupSelectionResult` 内部硬编码，Registry 不是兜底语义的唯一数据源。

**实际影响：**

- 当前默认 Registry 全部 enabled，Runtime 传入 `registry.allToolNames()`，所以现有默认请求仍能拿到 47 个工具。
- 但稳定接口允许产生“全量兜底=true、实际只有部分工具或零工具”的结果。
- 如果将来 disabled group、生效工具集合或风险定义变化，DefaultSelector fallback 和 Runtime fallback 可能产生不同候选集合或元信息。
- 后续 Context 不能仅根据 `allToolsFallback()` 判断结果是否真的是当前全量候选。

**建议修正：**

- 把工厂改为 Registry 驱动，例如：

```java
allToolsFallback(ToolGroupRegistry registry, String reason)
```

- 工厂内部统一使用 `registry.allToolNames()`，并从 Registry 或明确的 fallback policy 获取 context keys、risk 和 aggregation 信息。
- DefaultSelector 的 UNKNOWN/null intent 和 Runtime 的 selector null/exception 必须调用同一个全量兜底构造入口。
- Registry 为空或 `allToolNames()` 为空时，不得设置 `allToolsFallback=true`，应退回明确的轻量降级结果。
- 测试应断言 `selectedToolNames` 与 `registry.allToolNames()` 完全相等，并删除用三个任意名称冒充全量结果的测试方式。

---

### P2-1：真实 LangChain4j 规格测试只覆盖 AC Manager，没有覆盖生产注册的全部 47 个工具

**证据：**

- `ToolGroupRegistryTest.java:432-449` 只扫描 `VehicleAcManager.class`。
- AC Manager 当前只有 15 个 `@Tool`。
- `AIAgentService.kt:336-341` 实际注册 10 个 Manager / 工具提供者：Weather、Door、Window、Seat、AC、Chassis、Fragrance、Speed、DMS、Vision。
- 当前测试没有自动覆盖其余 32 个真实工具，也没有断言完整真实 specs 校验结果 `valid()==true`。

**实际影响：**

- 当前人工扫描确认 47 对 47 一致，没有发现真实漂移。
- 但以后 Window、Seat、Weather、Vision 等 Manager 修改 `@Tool` 名称时，现有“真实规格测试”不会失败。

**建议修正：**

- 反射扫描与 Service 注册集合一致的全部 10 个 class，合并真实 specs。
- 将完整 specs 传入 `validateAgainstToolSpecifications(...)`，断言 `valid()==true`。
- 同时断言真实 toolName 唯一，避免重复名称被 `Set` 静默去重。

---

### P2-2：上一轮修复的关键异常路径仍缺少直接回归测试

**已补充：**

- selector 返回 null 的 Runtime 全量兜底测试。
- `RequestSessionFactory` 收到 null selection 的轻量兜底测试。

**仍缺少：**

- Runtime Registry 为 null 时，selector 返回 null / 抛异常应走轻量 fallback 的测试。
- `ToolGroupContextProvider` 在 `allToolsFallback=true + Registry=null` 时仍使用摘要的测试。
- `allToolNames()` 排除 disabled group 的测试。
- Runtime 全量 fallback reason 写入 Trace 的测试。
- `allToolsFallback(...)` 遇到 null / 空候选集合的契约测试。

**影响：**

这些分支目前静态代码看起来已经部分修正，但没有测试保护，后续重构很容易恢复上一版问题。

---

### P2-3：风险等级合法值仍未被接口约束

**证据：**

- `ToolGroup` 构造函数仍接受任意 `String riskLevel`。
- `ToolGroupRegistry.riskLevelToInt(...)` 对未知值直接返回 0，即按 `LOW` 处理。
- 计划要求合法值仅允许 `LOW`、`MEDIUM`、`HIGH`。

**实际影响：**

- 当前默认 Registry 全部使用合法值，不影响现有路径。
- 后续新增组如果误写风险字符串，会被静默降级成 LOW，未来 Context 可能低估风险。

**建议修正：**

- 最小方案是在 `ToolGroup` 构造时校验合法字符串并抛出明确异常。
- 或新增 `ToolGroupRiskLevel` enum；若坚持最小改动，本轮不必引入 enum。
- 增加非法风险值测试。

---

### P2-4：overview、工作总结和测试结果仍未完整反映当前代码与确认后的边界

**工作总结偏差：**

- `docs/act_summary/2026-07-10-toolgroup-interface-improvement-summary.md:86` 仍称 DefaultSelector 使用 `full()`，当前代码已改为 `enriched()`。
- 总结没有记录本轮复验修复：null-Registry fallback、enabled `allToolNames()`、Builder 默认值、ToolGroup 实例方法等。
- 总结仍写 toolgroup 约 37 个测试；当前新鲜结果为 52 个 ToolGroup 测试。

**测试结果偏差：**

- `docs/testresult/2026-07-10-toolgroup-interface-improvement-testresult.md` 仍记录上一轮测试状态。
- 当前新鲜全量结果为 39 个 suite、188 项测试、0 failure、0 error、0 skipped。

**overview 偏差：**

- overview 已正确说明二参方法是 SAM、单参方法是 default。
- 但仍声称未来 selector 可以直接覆盖单参方法获取完整输入；当前 Runtime 不调用单参方法，未来接入仍需要修改 Runtime 调用点。
- overview 和工作总结仍把未来动态工具应用主要描述为 AgentLoop 自行过滤；按已确认边界，应表述为未来 Context 统一决定本轮工具输入，AgentLoop / ModelCaller 只消费该结果执行调用。
- `ToolGroupSelectionResult` 类注释仍写“仅写入 RequestSession 和 Trace”，没有说明当前 `ToolGroupContextProvider` 已经读取并渲染该结果。

**建议修正：**

- 在代码修复完成后同步更新 overview、工作总结和 testresult。
- 文档必须区分“当前可用接口”和“未来需要接线后才可用的扩展入口”。

---

## 四、完整审查中的补充观察

### 4.1 `enriched(...)` 的 null 归一化顺序无效

`ToolGroupSelectionResult.enriched(...)` 在调用 `registry.toolNamesFor(groupIds)` 和 `groupIds.contains(...)` 后，才在构造参数中写 `groupIds != null ? groupIds : List.of()`。因此 groupIds 为 null 时仍会先抛 NPE。

当前 DefaultSelector 永远传非 null groupIds，不影响默认生产路径。建议删除无效的“null 归一化”表达，或在方法入口统一校验 / 归一化，避免接口给出错误的 null-safe 暗示。该项作为 P2 代码质量问题合并处理，不单独提高等级。

### 4.2 enabled 语义只修复了 `allToolNames()`

`requiredContextKeysFor(...)` 和 `highestRiskLevelFor(...)` 仍会读取 disabled group 元信息。当前默认组全部 enabled，不产生实际差异。后续如果 enabled 成为运行时配置，需要统一明确：disabled group 是否仍应贡献 context keys 和 risk。

该问题属于未来配置语义，不作为本轮阻断，但应在 Context 接管前明确。

---

## 五、验证记录

### 5.1 针对性测试

```text
.\gradlew.bat testDebugUnitTest \
  --tests com.hirain.aiagent.toolgroup.* \
  --tests com.hirain.aiagent.runtime.AgentRuntimeTest \
  --tests com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest \
  --tests com.hirain.aiagent.runtime.RequestSessionFactoryTest \
  --tests com.hirain.aiagent.context.*
```

结果：`BUILD SUCCESSFUL`。

### 5.2 全量单元测试

```text
.\gradlew.bat testDebugUnitTest
```

结果：`BUILD SUCCESSFUL`。

- test suite：39
- tests：188
- failures：0
- errors：0
- skipped：0

其中 ToolGroup 测试：

- `DefaultToolGroupSelectorTest`：13
- `ToolGroupRegistryTest`：36
- `ToolGroupSelectionInputTest`：3
- 合计：52

### 5.3 Android debug 构建

```text
.\gradlew.bat assembleDebug
```

结果：`BUILD SUCCESSFUL`。

### 5.4 工具名人工一致性检查

- 真实 `@Tool(name=...)`：47
- Registry 基础 toolName：47
- missing：0
- ungrouped：0

### 5.5 Diff 基础检查

`git diff --check` 未发现 whitespace error；只有工作区已有 Prompt 文本的 LF/CRLF 提示。

---

## 六、最终验收意见

本次修改不是无效返工。上一版 3 个 P1 中：

- Runtime null-Registry 伪全量：已修复。
- `allToolNames()` enabled 实现：已修复。
- 完整候选结果构造：普通选择结果已通过 `enriched(...)` 修复，但全量兜底仍未收口到 Registry，属于部分修复。

当前默认生产路径、188 项单测和 Android debug 构建均正常，当前 47 个真实工具名也与 Registry 一致。未发现 P0 或当前默认请求必现的运行时错误。

但是，`allToolsFallback=true` 仍允许任意/空列表冒充全量候选，而且 DefaultSelector 与 Runtime 使用不同的全量候选数据源。这与本轮“规范兜底语义、给未来 Context 提供稳定接口”的核心目标直接冲突。

**因此本轮复验仍不通过。建议只阻断 P1-1：统一 Registry 驱动的全量兜底构造，并补充对应测试。其余 P2 可以在同一修复轮完成，但不应扩展到 AgentLoop 动态绑定或 Context 详细设计。**
