# ToolGroup Interface Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 规范 ToolGroup 模块的候选工具包、候选工具名、上下文需求和兜底语义，为后续 Context 模块统一管理工具上下文预留稳定接口。

**Architecture:** 本计划保持当前“IntentRouter -> ToolGroupSelector -> RequestSession -> ContextFrame”的主路径，不让 ToolGroup 在本阶段接管 LangChain4j 的实际 `toolSpecifications` 绑定。ToolGroup 继续作为 LangChain4j 工具基础设施之上的自研选择层，只输出候选选择结果、上下文需求、风险元信息和一致性校验结果，供后续 Context 模块消费。

**Tech Stack:** Java, Kotlin, Android Gradle Plugin, JUnit4, LangChain4j `ToolSpecification`, 现有 `ToolRegistry` / `ToolDispatcher` / `ContextOrchestrator`。

---

## 1. 工作边界

### 本轮要做

- 规范 `ToolGroupSelector` 的输入和输出契约，使后续规则选择器、小 LLM 选择器、子 agent 选择器可以共用同一个接口边界。
- 保留当前 `DefaultToolGroupSelector` 的简单规则，不引入小 LLM、不引入子 agent、不引入复杂语义路由。
- 当默认选择器或 Runtime 选择不出工具组时，兜底选择 `ALL_SAFE_DEMO_GROUP`，并返回该组下的全部 toolName。
- 为外部模块提供稳定查询接口：选中组、候选 toolName、需要的 context key、最高风险等级、是否聚合组、是否全量兜底。
- 增加 ToolGroupRegistry 与 LangChain4j `ToolSpecification` 的一致性校验能力，降低手写 toolName 漏同步风险。
- 在 `ToolGroupContextProvider` 中为全量兜底结果提供轻量渲染，避免把当前全量候选 toolName 全部写入首轮模型上下文。
- 更新 ToolGroup overview 文档，修正当前测试覆盖和“暂不动态绑定”的边界表述。

### 本轮不做

- 不修改 `AgentLoopOrchestrator` 中的 `ChatRequest.builder().toolSpecifications(...)` 绑定逻辑。
- 不实现 per-request 动态工具绑定。
- 不修改 Context 注入流程，不新增 ContextProvider，不改变 `ContextExtraPreProcessor` 行为；本轮唯一允许的 Context 侧改动是 `ToolGroupContextProvider` 对 `allToolsFallback` 的摘要渲染。
- 不让 `RequestSessionFactory` 持有 `ToolGroupRegistry` 或自行构造全量兜底结果；Factory 仅保留最后防线的轻量 null 降级。
- 不引入小 LLM 或子 agent，只在接口上预留未来替换点。
- 不新增第三方依赖，不修改 Gradle / AGP / Version Catalog。
- 不调整真实车控 tool 的业务实现，不修改 `VehicleStateMachine`。

### 明确假设

- `CHAT` 是明确选择结果：普通闲聊继续返回 `CHAT_ONLY_GROUP`，不视为“选择不出”。
- `UNKNOWN` 且没有弱车载关键词命中时，视为“选择不出明确工具组”，按用户要求兜底返回 `ALL_SAFE_DEMO_GROUP` 和全量 toolName。
- `intentResult == null`、`intentTag == null`、选择器异常、选择器返回 null，均视为“选择不出”，生产链路应兜底为全量 toolName。
- 全量 toolName 兜底由 `DefaultToolGroupSelector` 和 `AgentRuntime` 负责；`RequestSessionFactory` 不参与全量 toolName 构造。
- `BASIC_STATUS_GROUP` 是上下文需求标记组，不是实际工具组，不产生 toolName。
- `ALL_SAFE_DEMO_GROUP` 只是候选全量工具集合，不代表本阶段已经限制或授权 LLM 实际可调用工具。
- `ToolGroupSelector` 继续保持二参 `select(IntentResult, String)` 作为唯一抽象方法，避免破坏现有 lambda；新增的 `ToolGroupSelectionInput` 先作为 default 方法和未来 selector 的输入载体。
- `ToolGroupRegistry` 默认实现是不可变注册表，本轮只要求 Runtime、Selector、Context 使用同一套默认定义，不强制三者持有同一个对象实例。

---

## 2. 文件结构与职责

### 计划修改的生产代码

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelector.java`
  - 保持二参选择方法为唯一抽象方法，新增面向输入对象的 default 选择方法，避免破坏现有 lambda。

- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionInput.java`
  - 封装 ToolGroup 选择所需输入：`IntentResult`、原始用户输入、`inputType`、`userId`、`sessionId`、`personaId`。
  - 设计原因：未来替换为小 LLM 或子 agent 时，不必继续扩展 `select(IntentResult, String)` 的参数列表。

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionResult.java`
  - 明确该结果是“候选选择结果”，不是“实际绑定结果”。
  - 增加 enriched 候选工厂、全量兜底工厂、是否全量兜底字段、上下文 key / 风险信息承载字段。

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroup.java`
  - 保持不可变元数据模型，补充是否上下文标记组、是否聚合组的查询语义。
  - 优先通过方法推导，不急于改构造函数，减少调用点改动。

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
  - 增加 `allToolNames()`、`requiredContextKeysFor(...)`、`highestRiskLevelFor(...)`、`containsAggregationGroup(...)`、`validateAgainstToolSpecifications(...)` 等查询能力。
  - 保持 registry 只做查询和校验，不执行 tool，不替代 `ToolRegistry`。

- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryValidationResult.java`
  - 表示 ToolGroupRegistry 与 LangChain4j `ToolSpecification` 的一致性校验结果。
  - 包含 missing toolName、ungrouped toolName、是否通过、可读摘要。

- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
  - 继续使用当前简单规则。
  - 调整“选择不出”兜底为 `ALL_SAFE_DEMO_GROUP` + 全量 toolName。
  - 将内部结果补齐上下文 key、最高风险等级、聚合组标识、全量兜底标识。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
  - 仅在 ToolGroupSelector 异常或返回 null 时，使用同一套全量兜底结果。
  - 不改变 Runtime 执行 AgentLoop 的方式，不强制与外部 ContextOrchestrator 持有同一个 registry 实例。

- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
  - 当外部传入 null `ToolGroupSelectionResult` 时，继续执行轻量兜底，避免 Factory 持有 ToolGroupRegistry。

- Modify: `app/src/main/java/com/hirain/aiagent/context/provider/ToolGroupContextProvider.java`
  - 当 `ToolGroupSelectionResult.allToolsFallback()` 为 true 时，只渲染全量兜底摘要，不展开当前全部候选 toolName。

### 计划修改的测试

- Modify: `app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`
  - 覆盖 `UNKNOWN` 无弱关键词、null intent、明确 CHAT、弱车载关键词、全量兜底标识。

- Modify: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`
  - 覆盖上下文 key 汇总、最高风险等级、聚合组识别、全量 toolName、LangChain4j spec 一致性校验。

- Create: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionInputTest.java`
  - 覆盖输入对象默认值、不可变性、旧接口兼容路径。

- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`
  - 覆盖 selector 返回 null / 抛异常时 Runtime 兜底为全量工具。

- Modify: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeToolGroupTraceTest.java`
  - 覆盖全量兜底结果仍写入 trace，且不新增 trace 字段。

- Modify: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`
  - 覆盖 null selection result 的轻量兜底行为，明确 Factory 不构造全量工具。

- Modify: `app/src/test/java/com/hirain/aiagent/context/provider/ToolGroupContextProviderTest.java`
  - 覆盖 `allToolsFallback = true` 时只渲染摘要，不展开全量 toolName。

### 计划修改的文档

- Modify: `docs/overview/toolgroup-module-overview.md`
  - 更新 ToolGroup 当前定位、测试覆盖、fallback 策略和暂不动态 binding 的边界。

---

## 3. 阶段一：规范选择输入与接口兼容

**目标：** 先新增 ToolGroup 选择输入对象，并保持现有 `ToolGroupSelector` 二参 lambda 兼容，使当前规则选择器和未来小 LLM / 子 agent 选择器具备同一组输入扩展入口。

### Task 1.1：新增 `ToolGroupSelectionInput`

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionInput.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionInputTest.java`

- [ ] 新增不可变输入对象，字段包含：
  - `IntentResult intentResult`
  - `String userInput`
  - `String inputType`
  - `String userId`
  - `String sessionId`
  - `String personaId`
- [ ] 提供静态工厂方法 `of(IntentResult intentResult, String userInput)`，用于兼容当前选择器调用方式。
- [ ] 提供 Builder，用于未来从 `AgentRequest` 或子 agent 输入中携带更多上下文。
- [ ] 字符串字段统一做 null 安全处理，`inputType` 默认 `TEXT`，`personaId` 默认 `chat`。
- [ ] 新增测试：
  - `of_usesIntentAndUserInput()`
  - `builder_defaultsInputTypeAndPersona()`
  - `builder_preservesUserSessionPersona()`

**验收标准：**
- 输入对象不依赖 Android 类型。
- 输入对象不依赖 LangChain4j 类型。
- 当前 `select(IntentResult, String)` 能无损转换到 `ToolGroupSelectionInput`。

### Task 1.2：升级 `ToolGroupSelector` 接口

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelector.java`
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`

- [ ] 保持当前 `ToolGroupSelectionResult select(IntentResult intentResult, String userInput)` 为唯一抽象方法，不改变 Java SAM 签名。
- [ ] 新增 default 方法 `select(ToolGroupSelectionInput input)`，内部从 input 拆出 `intentResult` 和 `userInput` 后委托二参抽象方法。
- [ ] `DefaultToolGroupSelector` 继续实现二参抽象方法，避免改造现有生产调用链。
- [ ] 保留 `AgentRuntimeTest`、`AgentRuntimeToolGroupTraceTest` 中现有二参 lambda 写法，证明 lambda 兼容性未破坏。
- [ ] 新增至少一个测试直接调用 `select(ToolGroupSelectionInput input)` default 路径，证明未来 selector 可以通过输入对象扩展调用入口。
- [ ] 在计划和注释中明确：本阶段 input 对象是接口预留，不强制当前规则选择器消费 `personaId` / `inputType` 等扩展字段。

**验收标准：**
- 现有二参 lambda 不需要重写，项目不会因为 SAM 签名变化产生编译错误。
- 新接口能支持未来小 LLM / 子 agent 选择器扩展。
- 本阶段不引入任何 LLM 调用。

### 阶段一测试

- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.toolgroup.*`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] 如果 Gradle wrapper 因 `C:\Users\yala5\.gradle\wrapper\...\*.lck` 拒绝访问失败，按当前 Codex 权限流程申请提升权限后重跑同一命令。

---

## 4. 阶段二：增强 Registry 查询能力与 LangChain4j 一致性校验

**目标：** 让 Context 后续可以直接从 ToolGroupSelectionResult / ToolGroupRegistry 获取上下文需求和风险信息，同时校验手写 toolName 是否仍与 LangChain4j `ToolSpecification` 对齐。

### Task 2.1：补齐 ToolGroupRegistry 查询接口

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroup.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] 新增 `allToolNames()`：返回 registry 中所有唯一 toolName，顺序与 `ALL_SAFE_DEMO_GROUP` 保持一致。
- [ ] 新增 `requiredContextKeysFor(List<ToolGroupId> groupIds)`：按 group 顺序合并去重 context key。
- [ ] 新增 `highestRiskLevelFor(List<ToolGroupId> groupIds)`：按 `LOW < MEDIUM < HIGH` 计算最高风险。
- [ ] 合法风险等级只允许 `LOW`、`MEDIUM`、`HIGH`；本轮保持字符串接口，但在 `ToolGroupRegistry` 中集中定义排序函数或私有常量，避免散落字符串比较。
- [ ] 新增 `containsAggregationGroup(List<ToolGroupId> groupIds)`：当包含 `COMMON_VEHICLE_GROUP` 或 `ALL_SAFE_DEMO_GROUP` 时返回 true。
- [ ] 在 `ToolGroup` 上新增实例方法 `isContextMarker()`：当 `toolNames` 为空且 `requiredContextKeys` 非空时返回 true。
- [ ] 在 `ToolGroup` 上新增实例方法 `isAggregation()`：当 `groupId` 为 `COMMON_VEHICLE_GROUP` 或 `ALL_SAFE_DEMO_GROUP` 时返回 true。
- [ ] 在 `ToolGroupRegistry` 上新增 `isContextMarkerGroup(ToolGroupId groupId)`：查到 group 后委托 `group.isContextMarker()`，未知 group 返回 false。
- [ ] 在 `ToolGroupRegistry` 上新增 `isAggregationGroup(ToolGroupId groupId)`：查到 group 后委托 `group.isAggregation()`，未知 group 返回 false。
- [ ] 保持 `ToolGroup` 构造函数不扩字段，避免一次性改动所有注册代码。
- [ ] 明确 `allToolNames()` 只统计 `enabled == true` 的 group；当前默认 registry 全部 enabled，因此当前结果仍等于 `ALL_SAFE_DEMO_GROUP` 的 toolName 集合。
- [ ] 明确 `validateAgainstToolSpecifications(...)` 也只校验 enabled group 的 toolName；disabled group 视为暂不参与候选工具集合。

**验收标准：**
- `BASIC_STATUS_GROUP` 的 `toolNames()` 仍为空。
- `AC_GROUP + BASIC_STATUS_GROUP` 汇总出的 context keys 包含 `user_id`、`vehicle_status`。
- `COMMON_VEHICLE_GROUP` 的最高风险为 `HIGH`。
- `CHAT_ONLY_GROUP` 的 context keys 为空、最高风险为 `LOW`。
- 风险等级排序和 enabled group 统计语义都有单测覆盖。

### Task 2.2：新增 Registry 校验结果模型

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryValidationResult.java`
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] 新增不可变校验结果类，字段包含：
  - `List<String> missingToolNames`
  - `List<String> ungroupedToolNames`
  - `boolean valid`
  - `String summary`
- [ ] `missingToolNames` 表示 ToolGroupRegistry 中声明了、但 LangChain4j `ToolSpecification` 中不存在的 toolName。
- [ ] `ungroupedToolNames` 表示 LangChain4j `ToolSpecification` 中存在、但 ToolGroupRegistry 没有覆盖的 toolName。
- [ ] `valid()` 仅在两个列表都为空时返回 true。
- [ ] `summary()` 使用固定格式，便于测试断言和日志排查：

```text
valid=<true|false>; missingToolNames=[a,b]; ungroupedToolNames=[c,d]
```

- [ ] 列表为空时输出 `[]`，列表非空时按原始校验顺序输出并用英文逗号连接。

**验收标准：**
- 校验结果不可变。
- 空差异时 `valid()` 为 true。
- 有 missing 或 ungrouped 时 `valid()` 为 false。
- `summary()` 在 valid、missing、ungrouped 三类测试中都有精确断言。

### Task 2.3：接入 LangChain4j ToolSpecification 校验

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupRegistry.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] 新增 `validateAgainstToolSpecifications(List<ToolSpecification> specs)`。
- [ ] 从 `ToolSpecification.name()` 提取真实 LangChain4j toolName。
- [ ] 使用 `allToolNames()` 与真实 spec name 集合做双向差异。
- [ ] 测试使用 LangChain4j 1.16.3 可编译的 builder 构造最小 `ToolSpecification` 列表，覆盖 valid、missing、ungrouped 三种结果：

```java
private static ToolSpecification spec(String name) {
    return ToolSpecification.builder()
            .name(name)
            .description("test tool")
            .build();
}
```

- [ ] 如果执行阶段发现当前依赖环境中 builder 构造方式不可用，则改用测试专用 `@Tool` manager + `ToolSpecifications.toolSpecificationsFrom(TestToolManager.class)` 反射生成 specs，不允许留下不可编译的伪代码。
- [ ] 不在生产启动流程中强制 fail fast；本阶段只提供校验接口和测试。

**验收标准：**
- ToolGroup 模块可以基于 LangChain4j 基础设施校验自己的手写分组。
- 校验接口不影响 `ToolRegistry.dispatch()`。
- 校验接口不影响 `AgentLoopOrchestrator` 的 tool binding。

### Task 2.4：扩展 `ToolGroupSelectionResult` 候选语义

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionResult.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupRegistryTest.java`

- [ ] 在完成 `ToolGroupRegistry.requiredContextKeysFor(...)`、`highestRiskLevelFor(...)`、`containsAggregationGroup(...)` 后，再扩展 Result 派生字段，避免 Result 先于数据源落地。
- [ ] 更新类注释，明确 `selectedToolNames` 是“候选工具名”，不是 LangChain4j 已绑定工具。
- [ ] 增加字段：
  - `List<String> requiredContextKeys`
  - `String highestRiskLevel`
  - `boolean allToolsFallback`
  - `boolean containsAggregationGroup`
- [ ] 保留现有 getter，并新增：
  - `requiredContextKeys()`
  - `highestRiskLevel()`
  - `allToolsFallback()`
  - `containsAggregationGroup()`
- [ ] 保留现有 `of(...)` 工厂方法作为兼容轻量工厂，默认补齐空 context keys、`LOW` 风险、非全量兜底，并在注释中明确“不保证派生字段完整”。
- [ ] 新增 `enriched(...)` 工厂方法，用于传入完整候选元信息：候选组、候选 toolName、context keys、最高风险、聚合组标识、reason、confidence、fallback 标识。
- [ ] 新增全量兜底工厂方法，要求调用方传入 `selectedToolNames`、`requiredContextKeys`、`highestRiskLevel` 和 `containsAggregationGroup`，避免结果类直接创建 registry。
- [ ] 明确生产不变式：`DefaultToolGroupSelector` 和 `AgentRuntime` 构造生产候选结果时必须使用 registry-enriched 路径，不直接使用旧 `of(...)`。
- [ ] 保留测试自定义 selector 使用旧 `of(...)` 的能力；这些测试如果断言派生字段，应改用 `enriched(...)`。

**验收标准：**
- 现有调用不因为新增字段编译失败。
- 新字段都不可变。
- 全量兜底结果必须同时包含 `ALL_SAFE_DEMO_GROUP` 和 registry 当前全量候选 toolName。
- 读取派生字段的生产路径不会拿到旧 `of(...)` 的默认空 context keys 或默认 `LOW` 风险。

### 阶段二测试

- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.toolgroup.*`
- [ ] Expected: `BUILD SUCCESSFUL`

---

## 5. 阶段三：调整默认选择器兜底与 Runtime 防御路径

**目标：** 保持 `DefaultToolGroupSelector` 简单规则现状，同时把“选择不出 toolgroup”统一兜底为全量候选 toolName。

### Task 3.1：调整 DefaultToolGroupSelector 兜底语义

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelector.java`
- Test: `app/src/test/java/com/hirain/aiagent/toolgroup/DefaultToolGroupSelectorTest.java`

- [ ] 保持已有明确映射：
  - `VEHICLE_AC -> AC_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_WINDOW -> WINDOW_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_SEAT -> SEAT_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_DOOR -> DOOR_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_CHASSIS -> CHASSIS_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_FRAGRANCE -> FRAGRANCE_GROUP + BASIC_STATUS_GROUP`
  - `VEHICLE_DMS -> DMS_GROUP + BASIC_STATUS_GROUP`
  - `WEATHER -> WEATHER_GROUP`
  - `VISION_QA -> VISION_GROUP`
  - `CHAT + 无弱车载关键词 -> CHAT_ONLY_GROUP`
  - `CHAT + 弱车载关键词 -> COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP`
- [ ] 调整 `UNKNOWN + 弱车载关键词`：继续返回 `COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP`。
- [ ] 调整 `UNKNOWN + 无弱车载关键词`：返回 `ALL_SAFE_DEMO_GROUP`，`selectionReason = fallback:unknown_all_tools`，`allToolsFallback = true`。
- [ ] 调整 `intentResult == null` 或 `intentTag == null`：返回 `ALL_SAFE_DEMO_GROUP`，`selectionReason = fallback:null_intent_all_tools`，`allToolsFallback = true`。
- [ ] 每次构建结果时，通过 registry 补齐：
  - `selectedToolNames`
  - `requiredContextKeys`
  - `highestRiskLevel`
  - `containsAggregationGroup`

**验收标准：**
- 普通闲聊不会被全量工具污染。
- 真正无法判断时返回全量候选 toolName。
- 兜底结果里的 `selectedToolNames` 非空，数量等于 `ALL_SAFE_DEMO_GROUP` 的工具数。

### Task 3.2：统一 Runtime selector 异常兜底

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/AgentRuntime.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeTest.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/AgentRuntimeToolGroupTraceTest.java`

- [ ] 在 `AgentRuntime` 内部保存一个 `ToolGroupRegistry` 字段，专门用于 selector 异常 / null 返回时构造全量候选 fallback。
- [ ] 当前 `AgentRuntime` 有 9 个 public 构造函数，实施时不要新增第 10 个 public 构造函数；应让现有 9 个构造函数全部继续汇聚到一个全参构造函数。
- [ ] 默认构造路径应通过 private helper 创建 selector/runtime fallback 配对，保证 `DefaultToolGroupSelector` 与 Runtime fallback 来自同一套默认 registry 定义；不要求与外部传入的 `ContextOrchestrator` 内部 registry 是同一个对象实例。
- [ ] 注入自定义 selector 的测试构造路径也必须为 Runtime fallback 配置默认 registry，保证 selector 抛异常时仍能构造全量 fallback。
- [ ] `AIAgentService` 已经外部构造 `ContextOrchestrator`，本计划不要求 Service 中的 Context registry 与 Runtime fallback registry 同实例，只要求二者来自 `ToolGroupRegistry.defaultRegistry()` 的同一套定义。
- [ ] 全参构造函数内部对 `toolGroupRegistry == null` 做防御；若 registry 为空，才退回 `ToolGroupSelectionResult.fallback(...)` 的轻量降级，避免 NPE。
- [ ] `selectToolGroupsSafely(...)` 中：
  - selector 返回正常结果：使用 selector 结果。
  - selector 返回 null：返回全量兜底结果，reason 为 `tool_group_selector_null_all_tools`。
  - selector 抛异常：返回全量兜底结果，reason 为 `tool_group_selector_exception_all_tools`。
- [ ] Trace 写入仍使用 `selectedGroupIds`、`selectedToolNames`、`selectionReason`，不增加新的 trace 字段。
- [ ] 所有全量兜底 reason 必须统一包含 `_all_tools` 后缀，便于在不新增 trace 字段的前提下从 trace 中识别全量兜底。

**验收标准：**
- Runtime 不会因为 selector 异常导致空工具候选。
- Runtime 不改变 AgentLoop 执行方式。
- 现有 cancel、context build、response mapping 行为不受影响。
- 新增 registry 字段不影响 `SessionIdResolver` 的现有构造链。
- 计划不再承诺 Runtime、Selector、Context 三者持有同一个 registry 对象实例，只承诺默认注册表语义一致。
- trace 可观测性通过 `selectionReason` 的 `_all_tools` 命名约定保证，本阶段不新增 `agent.tool_group.all_tools_fallback` 字段。

### Task 3.3：保持 RequestSessionFactory 轻量空值防线

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/runtime/RequestSessionFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/runtime/RequestSessionFactoryTest.java`

- [ ] 不给 `RequestSessionFactory` 注入 `ToolGroupRegistry`。
- [ ] 当传入 `toolGroupSelectionResult == null` 时，继续使用轻量兜底结果，reason 使用 `missing_tool_group_selection`。
- [ ] 在注释中明确：生产主路径的全量兜底由 `AgentRuntime.selectToolGroupsSafely(...)` 保证，Factory 这里只是最后防线。
- [ ] 保持 request null 的最小 RequestSession 构造逻辑。
- [ ] 保持 `orchestratorContext` 不直接塞入 selected tool 信息；该信息仍通过 `ContextFrame` 合并。

**验收标准：**
- `RequestSession.toolGroupSelectionResult()` 不为 null。
- Factory 层空选择结果允许保留 `CHAT_ONLY_GROUP + 空 toolNames` 的轻量降级。
- 不改变 RequestSession 现有字段含义。
- Runtime 正常路径不会向 Factory 传入 null selection result。

### Task 3.4：全量兜底时轻量渲染 ToolGroup Context

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/context/provider/ToolGroupContextProvider.java`
- Test: `app/src/test/java/com/hirain/aiagent/context/provider/ToolGroupContextProviderTest.java`

- [ ] 本任务是本轮唯一允许的 Context 包改动，范围仅限 `ToolGroupContextProvider` 展示 `ToolGroupSelectionResult` 的方式；不改 Context 架构、不新增 provider、不改 `ContextExtraPreProcessor`。
- [ ] 在 `provide(...)` 中读取 `ToolGroupSelectionResult.allToolsFallback()`。
- [ ] 当 `allToolsFallback()` 为 true 时，不调用逐行展开 toolName 的原有渲染分支。
- [ ] 输出固定摘要格式，`selectedToolCount` 使用 `toolNames.size()` 动态计算，不硬编码当前工具数量：

```text
【工具组上下文】
- allToolsFallback: true
- selectedToolCount: <toolNames.size()>
- summary: 选择器无法确定明确工具组，本轮仅记录全量候选工具；实际 LLM 可见工具仍由 AgentLoop 固定绑定决定。
```

- [ ] metadata 中继续写入 `selected_group_count`、`selected_tool_count`、`selection_reason`。
- [ ] 新增测试断言：
  - 输出包含 `allToolsFallback: true`。
  - 输出包含 `selectedToolCount: ` + `ToolGroupRegistry.defaultRegistry().allToolNames().size()`。
  - 输出不包含逐行工具名，例如不包含 `  - set_ac_status`。
  - 普通 AC 选择结果仍保留原有逐行 toolName 渲染。

**验收标准：**
- 全量兜底不会把当前全部候选 toolName 全量注入模型上下文。
- 非全量兜底场景保持原有 Context 文本结构。
- 不改变 `ContextExtraPreProcessor` 和 Context 注入路径。

### 阶段三测试

- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.toolgroup.*`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.runtime.AgentRuntimeTest --tests com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest --tests com.hirain.aiagent.runtime.RequestSessionFactoryTest`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.context.provider.ToolGroupContextProviderTest`
- [ ] Expected: `BUILD SUCCESSFUL`

---

## 6. 阶段四：文档更新与最终验证

**目标：** 让 overview 文档和测试结果反映新的接口边界，避免后续误以为 ToolGroup 已经做了动态 binding。

### Task 4.1：更新 ToolGroup overview 文档

**Files:**
- Modify: `docs/overview/toolgroup-module-overview.md`

- [ ] 更新模块定位：ToolGroup 输出候选工具包、候选 toolName、上下文需求、风险元信息。
- [ ] 明确本阶段仍不限制 LangChain4j 实际可见工具集。
- [ ] 更新 fallback 策略：
  - 明确 `CHAT` 仍是纯聊天。
  - 明确 `UNKNOWN` 无法判定时兜底全量候选 toolName。
  - 明确 selector 异常 / null 结果使用全量兜底。
  - 明确 `RequestSessionFactory` 不构造全量 toolName，只保留最后防线的轻量 null 降级。
- [ ] 更新 Context 渲染说明：
  - 普通选择结果继续渲染选中 toolName。
  - `allToolsFallback = true` 时只渲染摘要，不展开当前全部候选 toolName。
- [ ] 更新测试覆盖现状，删除“只有 ToolGroupContextProviderTest”的过期说法。
- [ ] 增加“未来替换为小 LLM / 子 agent 的路径”说明：
  - 新增 selector 实现类即可。
  - 复用 `ToolGroupSelectionInput` 和 `ToolGroupSelectionResult`。
  - 外层保持超时、异常、解析失败时全量兜底。
- [ ] 明确 LangChain4j 参与边界：
  - 本阶段使用 `ToolSpecification.name()` 做一致性校验。
  - 不使用 LangChain4j 做选择策略。
  - 不在本阶段改 `ChatRequest.toolSpecifications(...)`。

**验收标准：**
- 文档没有把候选 toolName 描述成实际绑定 toolName。
- 文档和代码 fallback 策略一致。
- 文档说明后续 Context 模块接管统一管理的方向。

### Task 4.2：最终测试与人工核验

**Files:**
- No production file change in this task.

- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.toolgroup.*`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.runtime.AgentRuntimeTest --tests com.hirain.aiagent.runtime.AgentRuntimeToolGroupTraceTest --tests com.hirain.aiagent.runtime.RequestSessionFactoryTest`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.context.*`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] Run: `.\gradlew.bat testDebugUnitTest --tests com.hirain.aiagent.context.provider.ToolGroupContextProviderTest`
- [ ] Expected: `BUILD SUCCESSFUL`
- [ ] 人工核验：
  - `AgentLoopOrchestrator.java` 中 `effectiveToolSpecs` 绑定逻辑未改。
  - `ContextExtraPreProcessor.java` 未改。
  - `ToolGroupContextProvider.java` 可以继续读取 `selectedToolNames`，但全量兜底时不展开当前全部候选 toolName。
  - `docs/overview/toolgroup-module-overview.md` 与新 fallback 语义一致。

### Task 4.3：记录测试结果

**Files:**
- Create: `docs/testresult/2026-07-10-toolgroup-interface-improvement-testresult.md`

- [ ] 记录执行过的 Gradle 命令。
- [ ] 记录每条命令结果。
- [ ] 如果某条命令因为本地 Gradle lock、Android SDK 或环境权限失败，记录失败原因和是否已重跑。
- [ ] 记录未覆盖风险：本阶段未验证真实 LLM tool calling，因为计划明确不做动态 binding。

**验收标准：**
- 测试结果文档能支持后续代码审查。
- 失败项不会被误写成通过。

---

## 7. 阶段汇总

### 阶段一交付物

- `ToolGroupSelectionInput`
- 保持二参 SAM 兼容的新版 `ToolGroupSelector` default 输入对象入口
- ToolGroup 接口级单测通过

### 阶段二交付物

- Registry 上下文 key、风险、聚合组、全量 toolName 查询接口
- LangChain4j `ToolSpecification` 一致性校验接口
- 扩展后的 `ToolGroupSelectionResult`
- Registry 单测通过

### 阶段三交付物

- Default selector 简单规则保留
- “选择不出”全量 toolName 兜底落地
- Runtime 防御路径与 selector 全量兜底语义一致
- RequestSessionFactory 保持轻量最后防线，不持有 ToolGroupRegistry
- ToolGroupContextProvider 对全量兜底做摘要渲染

### 阶段四交付物

- overview 文档更新
- targeted tests 和 context 相关测试结果记录

---

## 8. 风险与后续留口

- 全量兜底会让 Context 后续看到当前全部候选 toolName；本计划通过 `ToolGroupContextProvider` 摘要渲染降低首轮模型上下文膨胀，但不处理更完整的 token budget 策略。
- `ALL_SAFE_DEMO_GROUP` 包含高风险车控工具；本阶段只标记 `highestRiskLevel = HIGH`，不做执行拦截。
- 小 LLM / 子 agent selector 后续接入时，必须补充超时、取消、输出解析和 trace；本计划只通过 `ToolGroupSelectionInput` 预留接口，不实现模型调用。
- LangChain4j 一致性校验本阶段不 fail fast；后续可以考虑在 debug build 或 CI 中强制校验。
- 动态 tool binding 仍属于后续 Context / AgentLoop 协作阶段，本计划不触碰。
- `RequestSessionFactory` 的轻量 null 降级与 Runtime 全量兜底语义不同；这是有意边界：Factory 不拥有 ToolGroup 数据源，生产主路径应由 Runtime 保证不传 null。

---

## 9. 自检结果

- 需求覆盖：已覆盖候选工具包、候选工具名、上下文需求接口、Runtime/Selector 全量 toolName 兜底、LangChain4j 基础设施校验、暂不动态 binding、未来小 LLM / 子 agent 替换点。
- 评审修正：已处理 `RequestSessionFactory` 无 registry、`AgentRuntime` 构造链缺 registry、全量兜底 Context 文本膨胀、方法归属不清、summary 格式未指定、测试清单遗漏、UNKNOWN 断言变更等问题。
- 边界检查：未计划修改 `AgentLoopOrchestrator` tool binding，未计划修改 `ContextExtraPreProcessor` 注入流程，未计划新增依赖。
- 阶段数量：共四个阶段，按接口契约、Registry 能力、默认选择器与 Runtime 兜底及 ContextProvider 摘要渲染、文档与验证划分。
- 测试检查：每个阶段都有 targeted Gradle 测试，最终阶段包含 context 相关回归测试。
