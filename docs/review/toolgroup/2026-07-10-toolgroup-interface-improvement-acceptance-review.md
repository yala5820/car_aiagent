# ToolGroup Interface Improvement Phase 1-4 验收审查报告

**审查日期：** 2026-07-10  
**审查对象：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md` 及其 Phase 1-4 实现  
**工作总结：** `docs/act_summary/2026-07-10-toolgroup-interface-improvement-summary.md`  
**修订依据：** 本阶段只规范 ToolGroup 候选结果及其查询接口，为未来 Context 统一管理模型输入预留数据边界；不接入 AgentLoop 动态工具绑定，不要求本阶段启用小 LLM / 子 agent selector  
**验收结论：** **不通过，需要修复核心接口契约后复验**

---

## 一、本轮验收边界

### 本轮应验收

- ToolGroup 能根据 Intent 结果输出候选工具组和候选 toolName。
- `ToolGroupSelectionResult` 能稳定暴露候选工具名、上下文需求、风险、聚合组和兜底语义。
- `ToolGroupRegistry` 能提供全量候选工具名、上下文需求、风险和 LangChain4j `ToolSpecification` 差异校验接口。
- `DefaultToolGroupSelector` 继续保持当前简单规则。
- 无法选择明确 ToolGroup 时，默认生产路径能够兜底到全量候选工具名。
- Context 后续可以通过 `RequestSession` / `ContextFrame` 读取 ToolGroup 选择结果。
- 全量兜底进入 `ToolGroupContextProvider` 时使用轻量摘要，避免展开全部候选 toolName。

### 本轮明确不验收

- 不要求把候选 toolName 动态绑定到 `AgentLoopOrchestrator` 的 `ChatRequest.toolSpecifications(...)`。
- 不要求修改当前 `effectiveToolSpecs` 固定绑定方式。
- 不要求 `AgentRuntime` 在本阶段改为调用 `select(ToolGroupSelectionInput)`。
- 不要求小 LLM / 子 agent selector 在本阶段可直接投入生产。
- 不要求 Context 在本阶段执行最终工具白名单决策、风险拦截或 token budget 管理。

### 对上一版报告的修正

上一版报告把“`ToolGroupSelectionInput` 未进入 Runtime 生产调用链”列为 P1。按本次确认的任务边界，这不属于本阶段缺陷：

- `ToolGroupSelectionInput` 是未来 selector 的输入扩展载体，可以先作为预留类型存在。
- 未来 Context 实际消费的是 `ToolGroupSelectionResult`、`ToolGroupRegistry` 和 `ContextFrame` 中的候选结果，不依赖 Runtime 当前是否调用单参 selector 方法。
- 动态 `toolSpecifications` 绑定属于后续 Context 接管模型输入时的落地工作，本阶段不应提前验收。

因此，原 P1-1 已从本报告问题清单中删除，不再影响本轮验收。

---

## 二、已完成项

- 保留了 `ToolGroupSelector` 二参 SAM，现有 lambda 可以继续编译。
- 增加了 `ToolGroupSelectionInput`，为未来 selector 扩展输入字段预留数据类型。
- `ToolGroupSelectionResult` 已增加：
  - `requiredContextKeys`
  - `highestRiskLevel`
  - `allToolsFallback`
  - `containsAggregationGroup`
- `DefaultToolGroupSelector` 的明确意图映射仍保持简单规则。
- `CHAT` 无弱车载关键词时仍返回 `CHAT_ONLY_GROUP`。
- `UNKNOWN` 无弱关键词、null intent、selector 异常等默认生产路径能够返回 `ALL_SAFE_DEMO_GROUP`。
- 正常 Registry 配置下，全量兜底会在 `ToolGroupContextProvider` 中使用摘要渲染。
- Registry 已提供 toolName、context key、risk、aggregation 和 `ToolSpecification` 双向差异查询接口。
- 当前源码中的 47 个 `@Tool` 名称与 `ToolGroupRegistry` 当前登记的 47 个名称一致。
- 本轮没有修改 AgentLoop 的动态工具绑定逻辑，符合任务边界。

---

## 三、问题清单

### P1-1：完整候选结果仍可出现互相矛盾的派生语义

**证据：**

- `ToolGroupSelectionResult.full(...)` 允许调用方分别传入 groupIds、toolNames、context keys、risk、fallback 和 aggregation 标记，没有校验这些字段是否一致。
- `ToolGroupRegistryTest.java:150-168` 构造了 `AC_GROUP + BASIC_STATUS_GROUP`，但传入并断言 `containsAggregationGroup=true`；该组合与 Registry 的聚合组定义不一致。
- `AgentRuntime.java:261-262` 对任意非 null selector 结果直接放行，不区分 Registry 补齐结果和兼容轻量结果。
- 计划 Task 2.4 要求新增 Registry-enriched 工厂，并要求生产结果通过 enriched 路径构造；实现使用通用 `full(...)`，没有形成可识别的完整结果契约。

**实际影响：**

- 当前 `DefaultToolGroupSelector` 通过 Registry 计算派生字段，默认规则路径基本正确。
- 但接口本身无法保证 Context 将来读取到的 `requiredContextKeys`、risk 和 aggregation 一定与 groupIds 对应。
- 兼容工厂 `of(...)` 固定回填空 context keys 和 `LOW` risk；如果这类结果进入 Context，Context 无法判断它是“真实 LOW”还是“尚未补齐”。

**为什么属于本轮：**

本轮目标是给未来 Context 预留稳定结果接口。Context 可以暂时不消费这些字段，但接口必须能够区分完整候选结果与兼容轻量结果，否则后续 Context 无法可靠使用。

**建议修正：**

- 增加语义明确的 Registry-enriched 构造入口，由 Registry 根据 groupIds 统一推导 toolNames、context keys、risk 和 aggregation。
- `DefaultToolGroupSelector` 和 Runtime 的生产结果只使用 enriched / Registry 构造路径。
- 保留 `of(...)` 时，在接口注释中明确它是不完整兼容结果；后续 Context 不应把它当作完整候选结果。
- 增加非法字段组合测试，防止 groupIds 与 risk / aggregation / context keys 相互矛盾。

---

### P1-2：Runtime null-registry 路径会产生“全量兜底=true、工具列表为空”的无效结果

**证据：**

- `AgentRuntime.java:264-271` 在 `toolGroupRegistry == null` 时向 `allToolsFallback(...)` 传入空列表。
- `ToolGroupSelectionResult.java:106-117` 不检查列表是否为空，仍设置：
  - `selectedGroupIds=[ALL_SAFE_DEMO_GROUP]`
  - `allToolsFallback=true`
  - `fallbackUsed=true`
- 计划 Task 3.2 明确要求 Registry 为空时使用轻量 `fallback(...)`，而不是伪造全量结果。

**实际影响：**

- 默认生产构造路径会提供 Registry，因此正常请求不会触发。
- 但全参构造函数允许传入 null Registry；该异常路径会破坏 `allToolsFallback` 的稳定语义，并向 Context / Trace 输出误导数据。

**为什么属于本轮：**

“全量兜底”是本轮明确要规范的接口语义。无论该路径是否常见，`allToolsFallback=true` 都不应同时表示零个候选工具。

**建议修正：**

- Registry 为 null 时返回明确的轻量降级结果，并设置 `allToolsFallback=false`。
- 或在 Runtime 构造时保证 Registry 永不为 null，禁止无数据源的全量兜底。
- 为 `allToolsFallback(...)` 增加空列表约束或改为由 Registry 创建。

---

### P1-3：`allToolNames()` 没有真正实现“所有 enabled 工具组”的接口语义

**证据：**

- `ToolGroupRegistry.java:180-182` 直接返回 `ALL_SAFE_DEMO_GROUP` 的 toolNames。
- 该方法没有遍历 enabled 业务组，也没有根据各业务组 enabled 状态重新计算。
- `validateAgainstToolSpecifications(...)` 和 Runtime 全量 fallback 都依赖 `allToolNames()`。

**实际影响：**

- 当前默认 Registry 的所有组均为 enabled，且 `ALL_SAFE_DEMO_GROUP` 是在创建 Registry 时动态合并，因此当前 47 个工具名结果正确。
- 如果以后新增 enabled 业务组但遗漏更新聚合组，`allToolNames()` 会漏工具。
- 如果以后禁用业务组但聚合组仍保留旧名称，`allToolNames()` 仍会返回被禁用工具。

**为什么属于本轮：**

计划明确把 enabled group 统计语义写入 Registry 稳定接口。当前实现只在现有默认数据下结果正确，没有实现接口承诺本身。

**建议修正：**

- 从 enabled 的非聚合业务组动态合并唯一 toolName；或者让聚合组严格根据 enabled 子组动态生成。
- 增加可构造的 disabled group 测试数据，验证 `allToolNames()` 和一致性校验都排除 disabled 工具。

---

### P2-1：ContextProvider 在 Registry 缺失时会绕过全量摘要

**证据：**

- `ToolGroupContextProvider.java:44-48` 先处理 Registry null，并直接进入 `fallbackResult(toolNames)`。
- `allToolsFallback` 摘要分支位于 `:50-53`，Registry null 时不会执行。
- `fallbackResult(...)` 会逐项展开全部 toolName。

**影响：**

- 正常 Context 配置不受影响。
- Registry 缺失的降级路径会重新产生本轮计划要避免的全量文本膨胀。

**建议修正：**

- 先判断 `allToolsFallback`，再判断 Registry 是否可用；或者让 Registry-null fallback 同样执行摘要渲染。
- 增加 `allToolsFallback + null Registry` 测试。

---

### P2-2：`ToolGroupSelectionInput.Builder` 的 persona 默认值与计划不一致

**证据：**

- `ToolGroupSelectionInput.java:64-67` 中 `inputType` 默认 `TEXT`，但 `personaId` 默认 null。
- `ToolGroupSelectionInputTest.java:31-46` 名为 `builder_defaultsInputTypeAndPersona`，实际断言 personaId 为 null。
- 计划 Task 1.1 明确要求 Builder 的 personaId 默认 `chat`。

**影响：**

- 当前 Runtime 不使用该输入对象，因此不影响默认规则执行。
- `of(...)` 和 Builder 对 persona 默认值给出不同语义，会影响未来 selector 接入。

**建议修正：**

- 将 Builder 默认 personaId 统一为 `chat`，并同步测试。

---

### P2-3：计划要求的 `ToolGroup` 实例查询语义没有实现

**证据：**

- 计划要求在 `ToolGroup` 增加 `isContextMarker()` 和 `isAggregation()`。
- 当前 `ToolGroup.java` 仍只有字段 getter。
- `ToolGroupRegistry.java:226-237` 自己实现判断，没有委托 `ToolGroup`。

**影响：**

- Registry 已提供等价查询，当前功能可用。
- 但 Phase 2 交付物与计划不完全一致，聚合语义仍硬编码在 Registry。

**建议修正：**

- 按计划补齐实例方法，让 Registry 统一委托 group 元数据。
- 明确 risk 合法值；当前未知 risk 会被静默按 `LOW` 处理。

---

### P2-4：一致性校验算法已实现，但缺少真实生产规格回归测试

**证据：**

- `ToolGroupRegistryTest.java:365-375` 从 `registry.allToolNames()` 构造 `ToolSpecification`，再与同一 Registry 比较。
- 该测试能验证集合差异算法，但不能自动发现真实 Manager 的 `@Tool` 新增、删除或改名。

**当前事实：**

- 本次人工扫描真实 `@Tool(name=...)` 共 47 个。
- 当前 ToolGroupRegistry 基础工具列表也是 47 个，当前名称集合一致。
- 计划只要求提供校验接口，不要求生产启动时 fail fast，因此该项不再作为 P1 阻断。

**建议修正：**

- 增加一条使用真实 Manager class 的 `ToolSpecifications.toolSpecificationsFrom(...)` 回归测试。
- 或使用与 Service 相同的 `ToolRegistry.registerAll(...)` 集合生成真实 specs 后校验。

---

### P2-5：计划要求的部分测试和文档陈述未完成

**缺失测试：**

- selector 返回 null 时的 Runtime 全量兜底测试。
- Runtime 全量 fallback 的 Trace reason 测试。
- `RequestSessionFactory.create(..., null selection)` 的轻量最后防线测试。
- Runtime Registry 为 null 的降级测试。
- enabled / disabled group 的统计语义测试。

**文档偏差：**

- 工作总结声称 `AgentRuntimeTest` 已覆盖 selector 异常和 null，实际只有异常路径。
- overview 把单参 selector 写成主抽象方法、把二参方法写成兼容 default，与代码相反。
- overview 声称未来 selector 只需实现单参方法即可；这属于未来接入说明错误，但不影响本轮 Context 输出接口。
- 实现计划中的 task checkbox 仍全部未勾选，与“Phase 1-4 全部完成”的总结缺少对应记录。

**建议修正：**

- 补齐计划明确要求的测试。
- overview 应准确说明：当前二参方法是 SAM，单参方法是未来扩展入口，本阶段 Runtime 尚未调用单参入口。
- 修正工作总结中的测试覆盖数量和范围。

---

## 四、不计入本轮缺陷的后续事项

以下事项属于未来 Context / AgentLoop 协作阶段，不作为本轮验收问题：

- 根据候选 toolName 动态过滤 LangChain4j `ToolSpecification`。
- 让每轮 `ChatRequest.toolSpecifications(...)` 使用 Context 决定的工具集合。
- 小 LLM / 子 agent selector 的超时、取消、解析、Trace 和生产接线。
- Context 根据 risk 执行高风险确认或拦截。
- Context 根据 token budget 决定是否展开完整工具描述或参数 schema。

`ALL_SAFE_DEMO_GROUP.requiredContextKeys` 当前为空。全量兜底是否需要聚合 `user_id`、`vehicle_status`，属于未来 Context 如何解释和消费上下文需求的策略决定；在该策略尚未确认前，本报告不再把它判定为本轮 P1 缺陷。但后续 Context 设计必须明确该语义，不能默认空列表一定代表“不需要任何车辆上下文”。

---

## 五、阶段验收矩阵

| 阶段 | 已完成 | 未完成或不符合项 | 结论 |
|------|--------|------------------|------|
| Phase 1 | 输入对象已创建；二参 SAM 兼容；未来输入类型已预留 | Builder persona 默认与计划不一致 | 基本完成，有 P2 |
| Phase 2 | Registry 查询、差异算法和 Result 扩展字段已实现 | 完整结果不变式不足；allToolNames/enabled 语义未实现；ToolGroup 实例方法缺失 | 部分完成，有 P1 |
| Phase 3 | 默认简单规则保留；默认 Registry 下 UNKNOWN/null/exception 可全量兜底；正常 Context 下可摘要 | Runtime null-Registry 兜底语义错误；Context null-Registry 摘要分支错误；测试不完整 | 部分完成，有 P1 |
| Phase 4 | overview、summary、testresult 已生成；测试与构建为绿 | overview 有接口事实错误；summary 存在过度声明 | 部分完成，有 P2 |

---

## 六、验证记录

### 自动化验证

1. 针对性测试：

```text
.\gradlew.bat testDebugUnitTest \
  --tests com.hirain.aiagent.toolgroup.* \
  --tests com.hirain.aiagent.runtime.AgentRuntimeTest \
  --tests com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest \
  --tests com.hirain.aiagent.runtime.RequestSessionFactoryTest \
  --tests com.hirain.aiagent.context.*
```

结果：`BUILD SUCCESSFUL`。

2. 全量 debug 单元测试：

```text
.\gradlew.bat testDebugUnitTest
```

结果：`BUILD SUCCESSFUL`；39 个 test suite，185 项测试，0 failure，0 error，0 skipped。

3. Android debug 装配：

```text
.\gradlew.bat assembleDebug
```

结果：`BUILD SUCCESSFUL`。

4. Diff 基础检查：

```text
git diff --check
```

结果：未发现 whitespace error；仅出现已有文本文件的 LF/CRLF 提示。

### 人工核验

- 当前 `@Tool(name=...)` 源码扫描结果：47 个唯一名称。
- 当前 ToolGroupRegistry 基础工具列表：47 个名称。
- 两者当前集合一致。
- AgentLoop 的动态 `toolSpecifications` 绑定未纳入本轮改动，符合任务边界。
- 本轮未验证真实 LLM tool calling，符合“不做动态 binding”的计划边界。

---

## 七、建议修复顺序

1. 收紧完整候选结果构造语义，让 Context 后续可以识别并信任 Registry-enriched 结果。
2. 修复 Runtime null-Registry fallback，禁止“全量=true、工具为空”。
3. 修复 `allToolNames()` 的 enabled group 语义。
4. 修复 ContextProvider Registry-null 分支和 Builder persona 默认值。
5. 补齐 `ToolGroup` 实例方法、真实 specs 回归测试及其余计划测试。
6. 同步 overview、工作总结和测试结果说明。

---

## 八、最终验收意见

本轮没有发现 P0 编译或默认生产路径崩溃问题。当前简单规则、默认全量 fallback、候选结果传递和正常 Context 摘要路径已经可运行，当前 47 个 Registry toolName 也与源码中的 `@Tool` 名称一致。

按修订后的任务边界，以下内容不再阻止本轮验收：

- `ToolGroupSelectionInput` 尚未进入 Runtime 单参调用路径。
- ToolGroup 候选结果尚未动态绑定到 AgentLoop `toolSpecifications`。
- 小 LLM / 子 agent selector 尚未接入。

但完整候选结果仍缺少可靠不变式、null-Registry fallback 会产生伪全量结果、`allToolNames()` 没有真正满足 enabled group 接口语义。这三项直接影响未来 Context 能否稳定消费 ToolGroup 输出，仍属于本轮核心接口问题。

**因此本次 Phase 1-4 验收仍不通过，但阻断范围已收敛为 3 个 P1 接口问题。完成 P1 修复并补齐对应测试后即可复验；其余 P2 项不涉及 AgentLoop 动态绑定。**
