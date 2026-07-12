# ToolGroup 模块现状概况

**文档日期：** 2026-07-10
**代码审查范围：** `com.hirain.aiagent.toolgroup` 包全部 7 个生产文件 + 2 个校验文件

---

## 一、模块定位：它在系统中干什么

ToolGroup 是 IntentRouter（意图识别）的下游消费者。它的核心职责：**根据意图识别结果，挑选出本轮请求的候选工具包、候选 toolName、上下文需求（requiredContextKeys）、风险元信息（riskLevel）和聚合组标识**。

这些候选结果通过 `ToolGroupSelectionResult` 传递给 `RequestSession`，再由 Context 模块（`ToolGroupContextProvider`）渲染进 LLM 首轮消息的文本提示中。

但有一个非常重要的现实：**挑选结果当前只进入了 Context 文本描述（LLM 看到哪些是"候选工具"），并没有真正限制 LLM 能调用的工具范围。** LLM 的实际可调用工具集仍然由 `AgentLoopOrchestrator` 构造时传入的 `toolSubset` 决定，这个值在 Service 启动时确定，属于"一个 persona 一个配置"，不会随每轮请求变化。动态工具绑定已经被列为后续版本的工作。

本阶段还补齐了一套 **LangChain4j 一致性校验机制**（`validateAgainstToolSpecifications()`），可以双向检查手写 toolName 与真实 `ToolSpecification` 之间的遗漏和多余项，降低手动维护的漏同步风险。

---

## 二、底层工具基础设施：LangChain4j + 自研混合

ToolGroup 不是凭空造出来的，它下面有一整套工具声明、注册、派发、绑定到 LLM 的管线。理解这条管线才能理解 ToolGroup 到底卡在哪个环节。

### 2.1 四层工具栈

```
Layer 4: Vehicle Manager（工具实现）
    VehicleAcManager, VehicleWindowManager, ...
    用 LangChain4j @Tool/@P 声明方法，内部调 VehicleStateMachine
    ──────────────────────────────────────────────
Layer 3: ToolGroup（工具分组与选择）★ 本文主角
    ToolGroupRegistry, ToolGroupSelector
    对 Layer 2 的工具做"分组→按意图挑选→生成文本提示"
    但选出来的结果不进 Layer 2 的绑定流程
    ──────────────────────────────────────────────
Layer 2: 自研工具注册与调度
    ToolRegistry, ToolDispatcher (com.hirain.aiagent.ai.langchain4j.tool)
    封装 LangChain4j 的反射扫描，自己实现了 arg0/arg1 位置参数解析
    ──────────────────────────────────────────────
Layer 1: LangChain4j 框架
    @Tool 注解、ToolSpecifications 反射扫描、
    ChatRequest.Builder.toolSpecifications() 绑定、
    ToolExecutionRequest LLM 调用格式
```

### 2.2 每一层具体用什么、谁自研的

**Layer 1 — LangChain4j 框架提供：**

| 能力 | LangChain4j API | 说明 |
|------|-----------------|------|
| 工具声明 | `dev.langchain4j.agent.tool.Tool` 和 `@P` | 注解在方法上，定义工具名称、描述、参数 schema |
| 反射扫描 | `ToolSpecifications.toolSpecificationsFrom(Class)` | 自动从 Manager 类中提取所有 `@Tool` 方法的 `ToolSpecification` |
| LLM 绑定 | `ChatRequest.Builder.toolSpecifications(List<ToolSpecification>)` | 把工具规格列表发给 LLM，告诉模型"你可以调用这些函数" |
| 调用格式 | `ToolExecutionRequest` | LLM 返回的工具调用请求，含 `name()` 和 `arguments()` |

**Layer 2 — 自研（`com.hirain.aiagent.ai.langchain4j.tool`）：**

`ToolRegistry`：
- 封装了 LangChain4j 的 `ToolSpecifications.toolSpecificationsFrom()`，把各 Manager 注册进去
- 内部维护 `toolName → ToolDispatcher` 映射表
- 对外提供 `getToolSpecifications()`（给 Layer 1 做 LLM 绑定用）和 `dispatch()`（给 Layer 1 执行工具调用用）

`ToolDispatcher`：
- 完全自研。用 Java 反射扫描目标对象的 `@Tool` 方法，建立 `方法名 → Method + 参数类型` 绑定
- **关键的适配逻辑：参数解析用位置参数 `arg0`/`arg1` 而非命名参数**。这是因为 qwen 系列模型输出的 `ToolExecutionRequest.arguments()` 是 `{"arg0": true, "arg1": 26}` 这种位置形式，而不是 `{"status": true, "temp": 26}` 这种命名形式。LangChain4j 的默认解析器不支持这种格式，所以自研了 `resolveParameters` 方法来做适配

**Layer 3 — 自研（`com.hirain.aiagent.toolgroup`）：**
- 即本文分析的模块，在 Layer 2 之上做分组和选择
- ToolGroupRegistry 中的 toolName 列表是**手动维护**的，与 Layer 2 的 ToolRegistry 没有编译时关联

**Layer 4 — 自研（`com.hirain.aiagent.tools.vehicle.*`）：**
- 约 10 个 Vehicle Manager 类，每个包含多个 `@Tool` 方法
- 内部委托给 `VehicleStateMachine` 做虚拟状态管理

### 2.3 工具绑定到 LLM 的实际路径

```
Service 启动
    │
    ▼
AgentConfigFactory.createTextPersona(...)
    ├─ toolRegistry.getToolSpecifications()  → 47 个 ToolSpecification
    ├─ config.toolSubset(null)               → 表示"用全量"
    │
    ▼
AgentLoopOrchestrator 构造
    ├─ effectiveToolSpecs = config.toolSubset() != null
    │     ? config.toolSubset()           ← 如果有白名单，用它
    │     : allToolSpecs                  ← 否则全量 47 个
    │
    ▼
每轮请求：ChatRequest.Builder
    .toolSpecifications(effectiveToolSpecs)  ← 绑定给 LLM，每轮都一样！
```

关键结论：**chat / friendly / concise 三种 TEXT persona 的 `toolSubset` 都是 `null`，意味着 LLM 实际可调用的永远是全量 47 个工具。** 唯一做了白名单过滤的是 scene persona（场景识别，按场景类型挑工具）和 vision_qa persona（直接传空列表）。

### 2.4 ToolGroup 选出的结果去哪了

```
ToolGroupSelector.select() → selectedToolNames (15个AC工具)
    │
    ▼
RequestSession.toolGroupSelectionResult
    │
    ▼
ContextFrame.selectedToolNames → ToolGroupContextProvider 渲染成文本
    │
    ▼
renderedExtraContext: "【工具组上下文】- selectedToolNames: set_ac_status, ..."
    │
    ▼
LLM 读到这段文字，知道"推荐用这15个工具"
    但 ChatRequest.toolSpecifications 依然是 47 个
    LLM 完全可以调用推荐列表之外的工具
```

ToolGroup 的结果"卡"在了 Context 文本层面，没有回流到 Layer 2 → Layer 1 的绑定链路中。要真正做到动态工具绑定，需要在 `AgentLoopOrchestrator.execute()` 入口处，把 `ContextFrame.selectedToolNames` 转换成对 `effectiveToolSpecs` 的过滤。

---

## 三、文件组成与职责

共 7 个生产文件，分四层：

### 第一层：数据定义

| 文件 | 做什么 |
|------|--------|
| `ToolGroupId.java` | 13 个工具组枚举名 |
| `ToolGroup.java` | 单个工具组的完整描述：中文名称、用途、包含的 toolName、context key、风险等级 |
| `ToolGroupSelectionInput.java` | 选择输入对象（6 字段）：封装 IntentResult + userInput + inputType + userId + sessionId + personaId，为未来小 LLM / 子 agent 选择器预留接口 |

### 第二层：注册与查询

| 文件 | 做什么 |
|------|--------|
| `ToolGroupRegistry.java` | 47 个工具分到 13 组，提供按 groupId 查询、按 toolName 反查、合并 toolName、风险计算、上下文 key 聚合、LangChain4j 一致性校验 |
| `ToolGroupRegistryValidationResult.java` | 一致性校验结果（missingToolNames / ungroupedToolNames / valid / summary） |

### 第三层：选择策略

| 文件 | 做什么 |
|------|--------|
| `ToolGroupSelector.java` | 接口：二参 `select(IntentResult, String)` 为抽象方法（SAM），单参 `select(ToolGroupSelectionInput)` 为未来扩展入口的默认方法 |
| `DefaultToolGroupSelector.java` | 默认实现：IntentTag → ToolGroupId 映射 + 弱车载关键词兜底 + 全量 ALL_SAFE_DEMO_GROUP 兜底 |
| `ToolGroupSelectionResult.java` | 不可变候选结果：selectedGroupIds / selectedToolNames / requiredContextKeys / highestRiskLevel / allToolsFallback / containsAggregationGroup |

---

## 四、13 个工具组一览

| 组 ID | 中文名 | 工具数 | 说明 |
|--------|--------|--------|------|
| CHAT_ONLY_GROUP | 纯聊天组 | 0 | 无工具，LLM 只回复不调用 |
| BASIC_STATUS_GROUP | 基础状态组 | 0 | 不包含工具，标记请求需要 `user_id` / `vehicle_status` 上下文 |
| AC_GROUP | 空调工具组 | 15 | 空调温度、风速、出风口、模式等 |
| WINDOW_GROUP | 车窗工具组 | 11 | 四窗+天窗+遮阳帘+除霜+后视镜加热 |
| SEAT_GROUP | 座椅工具组 | 11 | 四座加热通风+按摩+方向盘加热 |
| DOOR_GROUP | 车门工具组 | 1 | 车门闭锁/解锁（risk=HIGH） |
| CHASSIS_GROUP | 底盘工具组 | 2 | 底盘模式+车速控制（risk=HIGH） |
| FRAGRANCE_GROUP | 香氛工具组 | 2 | 香氛类型+浓度 |
| DMS_GROUP | 驾驶员监测工具组 | 3 | 疲劳检测+分心检测+情绪检测 |
| WEATHER_GROUP | 天气工具组 | 1 | 天气预报查询 |
| VISION_GROUP | 视觉工具组 | 1 | 前向摄像头交互 |
| COMMON_VEHICLE_GROUP | 通用车辆组 | 45 | 上述 7 个车辆组的全量合并（不含 Weather、Vision） |
| ALL_SAFE_DEMO_GROUP | 全量演示组 | 47 | 全部工具，仅用于调试/演示 |

**总计：47 个唯一 toolName，去重分布到 13 个组。**

---

## 五、选择策略：怎么选出工具的

`DefaultToolGroupSelector` 的规则非常简单：

### 5.1 IntentTag → ToolGroup 映射

```
VEHICLE_AC       → AC_GROUP + BASIC_STATUS_GROUP       (15 tools)
VEHICLE_WINDOW   → WINDOW_GROUP + BASIC_STATUS_GROUP    (11 tools)
VEHICLE_SEAT     → SEAT_GROUP + BASIC_STATUS_GROUP      (11 tools)
VEHICLE_DOOR     → DOOR_GROUP + BASIC_STATUS_GROUP      (1 tool)
VEHICLE_CHASSIS  → CHASSIS_GROUP + BASIC_STATUS_GROUP   (2 tools)
VEHICLE_FRAGRANCE→ FRAGRANCE_GROUP + BASIC_STATUS_GROUP (2 tools)
VEHICLE_DMS      → DMS_GROUP + BASIC_STATUS_GROUP       (3 tools)
WEATHER          → WEATHER_GROUP                        (1 tool)
VISION_QA        → VISION_GROUP                         (1 tool)
CHAT             → 弱车载关键词? COMMON_VEHICLE(45) : CHAT_ONLY(0)
UNKNOWN + 关键词 → COMMON_VEHICLE_GROUP + BASIC_STATUS_GROUP (45 tools)
UNKNOWN + 无关键词→ ALL_SAFE_DEMO_GROUP                   (47 tools, 全量兜底)
null intent/tag  → ALL_SAFE_DEMO_GROUP                   (47 tools, 全量兜底)
selector 异常/null→ ALL_SAFE_DEMO_GROUP                   (47 tools, Runtime 层兜底)
```

**全量兜底 reason 统一含 `_all_tools` 后缀**，便于在 Trace 中无需新增字段即可识别（如 `fallback:unknown_all_tools`、`tool_group_selector_null_all_tools`）。

**`RequestSessionFactory` 不参与全量兜底**，它只保留最后防线的轻量降级（CHAT_ONLY_GROUP + 空 toolName），生产主路径的全量兜底由 `AgentRuntime.selectToolGroupsSafely()` 统一保证。

### 5.2 弱车载关键词兜底

当意图为 CHAT 或 UNKNOWN 时，用关键词做二次判断。关键词列表：

> 车、空调、车窗、窗户、座椅、车门、底盘、悬架、香氛、香薰、dms、驾驶员

命中任意一个 → 选中 COMMON_VEHICLE_GROUP（45 个工具），表示"虽然是闲聊，但提到了车，可能想控车"。

### 5.3 BASIC_STATUS_GROUP 的作用

BASIC_STATUS_GROUP 本身 **不包含任何工具**。它只表示"本轮请求需要车辆基本状态信息作为上下文"。这是给下游 Context 模块看的语义标记，不影响工具选择结果。

---

## 六、数据流向

```
用户说"打开空调"
    │
    ▼
KeywordIntentRouter.route("打开空调", "TEXT")
    → IntentResult(IntentTag.VEHICLE_AC, HIGH, matchedKeywords=[空调])
    │
    ▼
DefaultToolGroupSelector.select(intentResult, "打开空调")
    → ToolGroupSelectionResult(
          groupIds=[AC_GROUP, BASIC_STATUS_GROUP],
          toolNames=[set_ac_status, set_ac_drive_temp, ...共15项],
          reason="intent:VEHICLE_AC",
          confidence=HIGH)
    │
    ▼
AgentRuntime.startSession() 存入 RequestSession
    │
    ▼
ContextOrchestrator.build(session)
    → ToolGroupContextProvider 读取 selectionResult
    → 渲染成文本塞入 renderedExtraContext:
      【工具组上下文】
      - selectedGroupIds: AC_GROUP,BASIC_STATUS_GROUP
      - selectedToolNames: set_ac_status, ...
      - groupDescriptions:
        - AC_GROUP: 空调工具组 / 空调及温控相关控制 / risk=MEDIUM
        - BASIC_STATUS_GROUP: 基础状态组 / 车辆请求所需的基础上下文 / risk=LOW
    │
    ▼
ContextExtraPreProcessor 注入 LLM 首轮消息
    │
    ▼
LLM 看到：上下文说明 + 用户消息"打开空调"
```

关键点：**LLM 看到的"工具组上下文"是一段文本描述，不是 LangChain4j 的工具绑定。** 实际 LLM 能调用的工具范围取决于 `AgentLoopOrchestrator` 的 `toolSubset` 参数。

---

## 七、尚未实现的能力

### 7.1 动态工具绑定（计划中最大的 todo）

**预期：** 每一轮请求中，`ContextFrame.selectedToolNames` 动态替换 `AgentLoopOrchestrator` 的 `toolSubset`，让 LLM **只拿到本轮需要的工具 specification**。

**现状：** `AgentLoopOrchestrator` 在构造时就固定了 `effectiveToolSpecs`，不支持 per-request 覆盖。ToolGroupSelector 的选择结果只能在 Context 文本中体现，无法实际约束 LLM 的 tool_calls。

**影响：** LLM 即便看到了"本轮的推荐工具是 set_ac_status"，它仍然可以调用 `set_window_status`（如果该工具在 persona 的 toolSubset 里）。上下文提示和实际可用工具之间有 gap。

### 7.2 风险等级未参与决策

`ToolGroup` 有 `riskLevel` 字段（LOW/MEDIUM/HIGH），DOOR_GROUP 和 CHASSIS_GROUP 被标记为 HIGH。但这个字段：

- 没有参与 ToolGroupSelector 的选组逻辑
- 没有影响工具执行阶段（ToolDispatcher 不看 riskLevel）
- 只在 Context 文本中作为组描述的一个标注出现

### 7.3 风险等级未参与执行层决策

`ToolGroup` 有 `riskLevel` 字段（LOW/MEDIUM/HIGH）。改进后风险信息已通过 `ToolGroupSelectionResult.highestRiskLevel()` 暴露给下游，但执行层（ToolDispatcher）仍不看 riskLevel，不存在高风险工具的执行拦截。

### 7.4 ToolGroup 定义与 @Tool 声明仍脱离（但已有校验）

ToolGroupRegistry 中的 toolName 列表是手动维护的。本阶段新增了 `validateAgainstToolSpecifications()` 校验接口，可以双向检查手写 toolName 与真实 `ToolSpecification` 之间的遗漏和多余项，但校验不会被强制 fail fast，需要人工或 CI 触发。

---

## 八、已知问题与潜在风险

| 问题 | 严重程度 | 说明 |
|------|---------|------|
| **工具绑定不动态** | 高（设计层面） | ToolGroup 选工具但不真绑定，LLM 的实际工具集是 persona 级别的固定配置。需要 per-request toolSubset 机制。 |
| **toolName 硬编码** | 中 | 新增 @Tool 方法后需手动同步到 ToolGroupRegistry。已有 `validateAgainstToolSpecifications()` 校验接口但不强制 fail fast。 |
| **弱关键词兜底可能误判** | 低 | 用户说"今天天气不错，适合开车出去" → 命中"车" → 选中 45 个车辆工具。实际上是在闲聊天气。 |
| **BASIC_STATUS_GROUP 语义模糊** | 低 | 它不包含工具，仅作为"需要基础上下文"的标记。但下游（Context 模块和 AgentLoop）并没有根据它额外注入上下文，标记的存在感很低。 |
| **全量兜底可能暴露 HIGH 风险工具** | 中 | `ALL_SAFE_DEMO_GROUP` 含 DOOR/CHASSIS（risk=HIGH）。`highestRiskLevel()` 已标记为 "HIGH"，但执行层未拦截。 |

---

## 九、未来替换为小 LLM / 子 agent 选择器的路径

当前选择器是硬编码的规则实现（`DefaultToolGroupSelector`）。本阶段已通过 `ToolGroupSelectionInput` 和 `ToolGroupSelectionResult` 预留了未来替换点：

- **新增 selector 实现类即可**：实现二参 `select(IntentResult, String)`（当前 SAM），单参 `select(ToolGroupSelectionInput)` 为默认委托方法，未来接入时可直接覆盖单参方法获取更多上下文。
- **输入上下文已就绪**：`ToolGroupSelectionInput` 包含 `intentResult`、`userInput`、`inputType`、`userId`、`sessionId`、`personaId`，小 LLM 或子 agent 可直接读取。
- **外层兜底不变**：无论哪种 selector 实现，超时、异常、解析失败时 `AgentRuntime.selectToolGroupsSafely()` 统一走全量 `ALL_SAFE_DEMO_GROUP` 兜底。
- **不使用 LangChain4j 做选择**：选择策略完全由自研 selector 实现掌控，LangChain4j 仅用于 `@Tool` 声明规范、`ToolSpecification` 生成和 LLM 绑定。
- **不在本阶段改 `ChatRequest.toolSpecifications(...)`**：动态工具绑定仍需单独设计 per-request toolSpec 过滤机制，涉及 `AgentLoopOrchestrator`、`AgentConfig` 和 trace 的联动。

---

## 十、总结

ToolGroup 模块经过本轮改进后，**定位比之前更加清晰**：

- **已实现**：
  - 47 个工具分 13 个组，给定意图后选出候选工具子集、上下文需求、风险元信息和聚合组标识
  - `ToolGroupSelectionResult` 统一承载候选结果，供 Context 模块以统一接口消费
  - 无法判断意图时全量兜底为 `ALL_SAFE_DEMO_GROUP`（47 tools），Runtime selector 异常同样全量兜底
  - 全量兜底时 Context 文本走轻量渲染，不展开 47 个 toolName
  - LangChain4j 一致性校验接口可用的双向检查
  - `ToolGroupSelectionInput` 为未来小 LLM / 子 agent 选择器预留了扩展点
- **未实现**：选出的工具子集没有与 LangChain4j 的 toolSpecifications 绑定挂钩，LLM 的实际调用能力仍由 persona 级别固定配置决定。这是下一阶段最需要补齐的能力。
