# Tool Safety Policy Engine 详细实施计划

> **实施状态说明（2026-07-13）：** 本文对应的 Demo Safety 基线已经实现：统一 `ToolSafetyEngine`、`ALLOW / DENY`、车门解锁与底盘模式规则、三条 Tool 路径接线、旧 Guard 清理及 `set_vehicle_spd` 移除均已完成。本文保留为已实施基线记录，不再作为待执行计划。后续单请求控制、ToolGroup fail-closed 和文本二次确认以 `docs/plan/2026-07-13-agent-p0-runtime-tool-safety-improvement-plan.md` 的自审修订版为准。

> 编写日期：2026-07-13  
> 计划状态：对应业务代码已实现并通过现有测试；本文归档为实施基线  
> 适用阶段：Demo 版本  
> 参考现状文档：`docs/overview/safety-guard-module-current-state.md`

---

## 1. 计划目标

本计划用于将现有 `SafetyGuard` 体系替换为一个独立、轻量、确定性的车控 Tool 安全审核模块。

新模块位于 LLM 产生 `ToolExecutionRequest` 之后、`ToolExecutor` 或 `ToolDispatcher` 真正执行工具之前，负责根据以下信息决定工具调用是否允许执行：

1. Tool 名称；
2. Tool 的标准化 JSON 参数；
3. 当前 `VehicleStateMachine` 中与该规则有关的车辆状态。

第一版审核结果仅包含：

- `ALLOW`：允许继续执行 Tool；
- `DENY`：拒绝执行 Tool，并携带稳定原因码和可交给 LLM 理解的中文原因。

本次改造同时删除旧 `SafetyGuard`、`SafetyVerdict`、`SafetyVetoTerminator` 等体系，删除对外暴露的 `set_vehicle_spd` Tool，并在确认无调用方的前提下删除遗留 `MainAgentLoop`。

---

## 2. 已确认的设计决定

以下内容已经在讨论中确认，实施时不再重新扩展或改变含义。

### 2.1 模块范围

- AgentLoop 只调用一个统一的 `ToolSafetyEngine`，不再持有或遍历 `List<SafetyGuard>`。
- 安全规则对所有 Persona 完全一致，不随 chat、friendly、concise、scene 等人格变化。
- Tool 没有注册专用安全规则时默认 `ALLOW`。
- 规则采用 Java 显式代码实现，不使用配置中心、规则 DSL、注解扫描或动态加载。
- 同一个 Tool 可以对应多条规则；规则按固定顺序执行，遇到第一个 `DENY` 立即返回。
- 安全模块只负责执行前业务安全判断；Tool 参数业务校验和车辆状态收敛继续由 `VehicleStateMachine` 负责。

### 2.2 首批业务规则

#### 车门解锁

- Tool：`set_door_lock`。
- `arg0=true` 表示锁门，始终允许。
- `arg0=false` 表示解锁，只有当前车速严格等于 `0 km/h` 时允许。
- 车速不等于 0 时拒绝解锁。
- 参数缺失、类型错误或车速无法读取时拒绝。

#### 底盘模式切换

- Tool：`set_chassis_mode`。
- 与座舱位置无关。
- 只有当前车速严格等于 `0 km/h` 时允许切换。
- 参数缺失、类型错误或车速无法读取时拒绝。
- 模式值是否合法仍由 `VehicleStateMachine.setChassisMode()` 校验，安全规则不重复维护合法模式列表。

### 2.3 DENY 后的 AgentLoop 行为

- 被拒绝的 Tool 不进入 `ToolExecutor` 或真实反射调度。
- DENY 结果仍以 LangChain4j `ToolExecutionResultMessage` 写回 ChatMemory，保证 Tool Request / Tool Result 配对完整。
- 删除当前“全部工具被否决后直接返回固定文本”的短路逻辑。
- DENY 写回后正常进入下一轮模型调用，让 LLM 根据拒绝原因生成一次自然语言解释。
- 第一版不增加专用“仅解释模式”。如果模型无视提示并重复调用相同 Tool，安全引擎会再次拒绝，现有最大迭代次数负责兜底。

建议的 DENY 回写格式：

```text
[SAFETY_DENY][DOOR_UNLOCK_REQUIRES_STOPPED]
车辆行驶期间不能解锁车门，请先停车。请向用户说明拒绝原因，不要再次调用该工具。
```

---

## 3. 设计边界与非目标

### 3.1 本次必须完成

- 新增统一 `ToolSafetyEngine` 和首批两条规则。
- 规则可以直接读取 `VehicleStateMachine` 中需要的车辆状态。
- `AgentLoopOrchestrator` 和 `TextAgentLoopOrchestrator` 的真实 Tool 执行路径全部接入新引擎。
- 所有 AgentLoop 实例共享同一个安全引擎和同一套规则。
- 旧 SafetyGuard 体系完全删除，不保留双轨兼容层。
- DENY 结果码、中文原因、工具执行记录和 Trace 信息保持一致。
- 删除 `set_vehicle_spd` 的模型可调用入口及相关 ToolGroup 声明。
- 删除确认无生产与测试调用方的 `MainAgentLoop`。

### 3.2 本次明确不做

- 不建立规则配置中心。
- 不设计规则 DSL。
- 不支持运行时新增、删除或热更新规则。
- 不按 Persona、用户或座舱位置切换规则集。
- 不实现权限系统、审批流或二次确认流程。
- 不为每种车辆状态建立单独 Provider。
- 不建立 `SpeedProvider`、`RuleRegistry`、依赖注入框架或通用风险等级系统。
- 不提前为尚未确定的业务编写十几条假想规则。
- 不把 `VehicleStateMachine` 的参数校验逻辑复制到安全规则中。

---

## 4. 目标运行流程

```text
LLM 返回 ToolExecutionRequest
        ↓
AgentLoop 调用一次 ToolSafetyEngine.check(request)
        ↓
ToolSafetyEngine 根据 toolName 查找规则列表
        ├─ 没有规则 → ALLOW
        └─ 存在规则
              ↓
        解析 request.arguments() 为 Gson JsonObject
              ├─ 解析失败 → DENY / INVALID_ARGUMENT
              └─ 解析成功
                    ↓
              构造 SafetyCheckContext
                    ↓
              按固定顺序执行 SafetyRule
                    ↓
              规则按需读取 VehicleStateMachine 当前状态
                    ├─ 首个 DENY → 跳过 ToolExecutor
                    │              写回 ToolExecutionResultMessage
                    │              下一轮交给 LLM 自然解释
                    └─ 全部 ALLOW → ToolExecutor / ToolDispatcher
                                       ↓
                                写回真实 Tool 结果
```

关键约束：

- AgentLoop 不知道存在多少条规则，也不参与规则选择。
- 安全引擎不依赖 `AgentLoopContext`、ChatMemory、Persona、Prompt 或 ToolGroup。
- ToolGroup 的风险元数据不作为安全判断依据；是否审核只由显式规则映射决定。
- 安全审核不替换 LangChain4j Tool Calling，只位于 `ToolExecutionRequest` 与项目自定义 ToolExecutor 之间。

---

## 5. 目标目录与文件职责

生产代码新增目录：

```text
app/src/main/java/com/hirain/aiagent/safety/
├── ToolSafetyEngine.java
├── SafetyCheckContext.java
├── SafetyDecision.java
├── SafetyRule.java
├── DefaultSafetyRules.java
└── rules/
    ├── DoorUnlockSafetyRule.java
    └── ChassisModeSafetyRule.java
```

### 5.1 `ToolSafetyEngine.java`

安全审核的唯一公开入口。

职责：

- 持有一个 `VehicleStateMachine` 引用；
- 持有不可变的 `Map<String, List<SafetyRule>>`；
- 根据 Tool 名称选择对应规则列表；
- 对有规则的 Tool 统一解析 JSON 参数；
- 构造 `SafetyCheckContext`；
- 按固定顺序执行规则并在首个 DENY 时短路；
- 无规则 Tool 默认放行；
- 捕获规则未预期异常并转换为稳定的 DENY，而不是让安全审核异常绕过执行前检查。

建议入口：

```java
public SafetyDecision check(ToolExecutionRequest request)
```

Engine 本身不包含车门、底盘等具体业务判断。

### 5.2 `SafetyCheckContext.java`

单次安全审核的轻量输入对象。

第一版只保存：

- `String toolName`；
- 已解析的 Gson `JsonObject arguments`。

它不保存完整 `AgentLoopContext`，也不提前复制所有车辆状态。具体规则需要什么车辆状态，就通过 Engine 传入的同一个 `VehicleStateMachine` 读取什么状态。

### 5.3 `SafetyDecision.java`

不可变审核结果对象。

建议在同一个文件中内置两个枚举，避免为简单枚举继续增加文件：

```text
DecisionType
├── ALLOW
└── DENY

ReasonCode
├── ALLOW
├── INVALID_ARGUMENT
├── SPEED_UNAVAILABLE
├── DOOR_UNLOCK_REQUIRES_STOPPED
├── CHASSIS_MODE_REQUIRES_STOPPED
└── RULE_EXECUTION_ERROR
```

对象字段：

- `DecisionType type`；
- `ReasonCode reasonCode`；
- `String reason`。

建议提供：

- `SafetyDecision.allow()`；
- `SafetyDecision.deny(ReasonCode, String)`；
- `isAllowed()`；
- `isDenied()`。

拒绝原因码用于测试、Trace 和程序判断；中文原因用于回写给 LLM。程序逻辑不得依赖中文原因文本。

### 5.4 `SafetyRule.java`

所有具体规则共同实现的最小接口：

```java
SafetyDecision check(
        SafetyCheckContext context,
        VehicleStateMachine vehicleStateMachine
);
```

不增加 `supports()` 方法，因为 Tool 与规则的对应关系由 `DefaultSafetyRules` 明确维护。

规则只调用 `VehicleStateMachine` 的读取方法，不调用任何状态修改方法。

### 5.5 `DefaultSafetyRules.java`

唯一的默认规则装配位置。

职责：

- 显式声明 Tool 名称与规则列表的对应关系；
- 使用固定顺序，保证同一个 Tool 多规则执行结果确定；
- 返回不可变 Map；
- 后续增加规则时，在 `rules/` 新增规则文件，并在此处增加一行映射。

第一版映射：

```text
set_door_lock
└── DoorUnlockSafetyRule

set_chassis_mode
└── ChassisModeSafetyRule
```

Map 类型：

```java
Map<String, List<SafetyRule>>
```

Map 的创建归 `DefaultSafetyRules`，运行时持有归 `ToolSafetyEngine`。`AIAgentService` 不逐条列出十几条规则，AgentLoop 也不接触该 Map。

### 5.6 `rules/DoorUnlockSafetyRule.java`

只负责 `set_door_lock` 的解锁安全判断。

执行顺序：

1. `arg0` 缺失或不是 boolean：`DENY / INVALID_ARGUMENT`；
2. `arg0=true`，表示锁门：`ALLOW`；
3. `arg0=false`，表示解锁：读取当前车速；
4. 车速读取失败：`DENY / SPEED_UNAVAILABLE`；
5. 车速等于 0：`ALLOW`；
6. 车速不等于 0：`DENY / DOOR_UNLOCK_REQUIRES_STOPPED`。

规则不处理“车门未关闭时能否锁门”，该逻辑继续由 `VehicleStateMachine.setDoorLock()` 负责。

### 5.7 `rules/ChassisModeSafetyRule.java`

只负责 `set_chassis_mode` 的静止状态判断。

执行顺序：

1. `arg0` 缺失或不是 String：`DENY / INVALID_ARGUMENT`；
2. 读取当前车速；
3. 车速读取失败：`DENY / SPEED_UNAVAILABLE`；
4. 车速等于 0：`ALLOW`；
5. 车速不等于 0：`DENY / CHASSIS_MODE_REQUIRES_STOPPED`。

规则不判断主驾驶、副驾驶或其他座舱位置，也不校验“普通模式、越野模式、雪地模式”等值是否合法。

---

## 6. 车辆状态读取设计

### 6.1 当前问题

当前 `VehicleStateMachine` 持有 AC、Door、Window、Seat、Speed、Chassis、Frag、DMS 八类状态，但对外的 `getSpeedStatus()`、`getDoorStatus()` 等方法主要返回中文 JSON 字符串，失败时还可能返回普通中文错误文本。

安全规则不应解析这些面向展示的字符串，也不应继续通过 `VehicleSpeedManager + SpeedProvider` 间接读取车速。

### 6.2 第一版方案

在 `VehicleStateMachine` 中增加规则当前确实需要的类型明确只读 getter，例如：

```java
public int getVehicleSpd()
```

`ToolSafetyEngine` 持有统一的 `VehicleStateMachine`，执行具体规则时将其传入。规则看到一个 Tool 调用后，直接读取该规则需要的状态并进行判断。

后续规则需要其他车辆状态时，再在 `VehicleStateMachine` 增加对应的只读 getter，例如：

- `isAnyDoorOpen()`；
- `isDoorLocked()`；
- `getChassisMode()`；
- 某个车窗当前开度；
- 驾驶员疲劳或分心等级。

本次不一次性增加全部状态 getter，也不建立 Provider 或完整状态快照。这样既不把设计限制在车速，也避免 Demo 阶段提前建设未使用的抽象。

### 6.3 状态不可用处理

- Demo 虚拟状态机中的车速通常始终可用。
- 如果读取状态抛出异常或未来真实车辆状态接入后返回不可用，涉及该状态的高风险规则必须 DENY。
- 具体状态读取失败由具体规则转换为状态类原因码，例如 `SPEED_UNAVAILABLE`。
- 规则自身未预期异常由 Engine 兜底转换为 `RULE_EXECUTION_ERROR`。
- 未注册安全规则的低风险 Tool 不读取车辆状态，直接默认 ALLOW。

---

## 7. 规则注册与扩展方式

新增一条规则时仅执行以下步骤：

1. 在 `safety/rules/` 下新增一个实现 `SafetyRule` 的类；
2. 如果需要新的车辆状态，在 `VehicleStateMachine` 增加一个类型明确的只读 getter；
3. 在 `DefaultSafetyRules.create()` 中把 Tool 名称映射到该规则；
4. 增加该规则的单元测试；
5. 如新增原因，向 `SafetyDecision.ReasonCode` 增加稳定原因码。

如果一个 Tool 后续存在多条独立安全约束，则在同一 Tool 的 List 中按明确顺序排列：

```text
some_high_risk_tool
├── RuleA
├── RuleB
└── RuleC
```

Engine 按 A → B → C 执行，首个 DENY 返回，不合并多个拒绝原因。

十几条规则仍采用同一方式维护，不引入更复杂的注册系统。

---

## 8. AgentLoop 接入方案

### 8.1 实例所有权

`AIAgentService` 在创建唯一 `VehicleStateMachine` 后，创建一个共享的 `ToolSafetyEngine`：

```text
AIAgentService
├── VehicleStateMachine（唯一实例）
└── ToolSafetyEngine（唯一实例，持有上述状态机和固定规则 Map）
```

同一个 Engine 注入以下运行路径：

- `TextAgentLoopOrchestrator`；
- `AgentLoopOrchestrator` 的 chat 路径；
- 动态创建的 scene `AgentLoopOrchestrator`；
- 其他未来通过 AgentLoop 执行 Tool 的 Persona。

无 Tool 的视觉路径不会触发安全审核，但也不存在独立或不同的人格安全规则。

共享 Engine 必须保持无请求级可变状态：规则 Map 初始化后不可修改，单次审核数据只存在于局部变量和 `SafetyCheckContext` 中。

### 8.2 从 AgentConfig 移除安全规则

`AgentConfig` 删除：

- `List<SafetyGuard> safetyGuards` 字段；
- `safetyGuards()` getter；
- Builder 中的默认值和 `safetyGuards(...)` 方法。

`AgentConfigFactory` 删除：

- 所有 `SafetyGuard` 实现导入；
- 各 Persona 的 `.safetyGuards(...)` 装配；
- 仅为旧规则服务的 `VehicleSpeedManager speedManager` 参数；
- `parseSpeed(...)`；
- `SafetyVetoTerminator` 装配。

原因：安全规则是全车统一策略，不属于 Persona 行为配置。

### 8.3 Orchestrator 修改

`AgentLoopOrchestrator` 与 `TextAgentLoopOrchestrator` 分别增加一个必需的 `ToolSafetyEngine` 字段和构造参数。

每个 Tool 调用的执行骨架统一为：

```java
SafetyDecision decision = toolSafetyEngine.check(request);

if (decision.isDenied()) {
    toolResult = formatSafetyDenyResult(decision);
} else {
    toolResult = toolExecutor.execute(request);
}

chatMemory.add(ToolExecutionResultMessage...);
loopContext.addToolResult(..., decision);
```

必须同时覆盖当前代码中的三处 SafetyGuard 循环：

1. `AgentLoopOrchestrator.execute(String, Map)` 遗留路径；
2. `AgentLoopOrchestrator.execute(RequestSession, ContextPrepareResult)` TEXT 兼容路径；
3. `TextAgentLoopOrchestrator` 当前主 TEXT 路径。

不得只修改其中一条路径。

### 8.4 删除全部否决短路

三条路径中现有 `vetoCount` 和“安全原因已阻止所有工具调用。”固定返回逻辑全部删除。

无论一轮中是一个还是多个 Tool 被拒绝，都必须：

1. 为每个 Tool 写入对应结果；
2. 不执行被拒绝的 Tool；
3. 完成整轮 Tool Result 配对；
4. `continue` 到下一轮模型调用，让 LLM 组织自然语言答复。

取消处理、Tool 异常处理和 Tool Result 配对规则保持现状，不借本次改造重构。

### 8.5 DENY 文本格式化

DENY ToolResult 的格式化逻辑应集中在 Engine 或安全模块的单个方法中，避免三个 AgentLoop 路径自行拼接出不同文本。

格式至少包含：

- 固定前缀 `[SAFETY_DENY]`；
- 稳定原因码；
- 中文拒绝原因；
- “向用户解释且不要重复调用”的简短提示。

AgentLoop 不根据中文文本进行逻辑判断。

---

## 9. Tool 执行记录与 Trace 迁移

### 9.1 `AgentLoopContext`

删除：

- `SafetyVerdict lastSafetyVeto`；
- `lastSafetyVeto()`；
- `setLastSafetyVeto(...)`。

原因：新流程在 DENY 后继续让 LLM 解释，不再依赖“最近一次 veto”终止循环。

保留工具执行历史，但 `addToolResult(...)` 参数改为 `SafetyDecision`。

### 9.2 `ToolExecutionRecord`

将：

```text
SafetyVerdict safetyVerdict
```

替换为：

```text
SafetyDecision safetyDecision
```

ALLOW、DENY、原因码和原因均通过该对象保留。执行异常时允许 decision 为 `null`，继续与当前异常记录语义保持一致。

### 9.3 Trace

`AgentTraceRecorder` 和 `TraceAttributeKeys` 从 Guard/Veto 术语迁移到 Engine/Decision/Deny 术语。

建议安全审核 Span 至少记录：

- 审核结果：ALLOW / DENY；
- 原因码；
- 拒绝原因（仅 DENY）；
- Tool 是否真正进入 dispatch。

删除“Guard 数量”属性，因为 AgentLoop 只调用一个 Engine，规则数量属于 Engine 内部实现，不再是 AgentLoop 指标。

需要同步更新：

- `AgentTraceRecorder.finishTool(...)` 参数类型；
- `finishToolSafetyCheck(...)` 签名和属性；
- `AgentTraceRecorderTest`；
- `ToolPhaseTraceTest`；
- 其他编译期引用 `SafetyVerdict` 的 Trace 测试。

---

## 10. 旧 SafetyGuard 体系删除清单

计划删除的生产文件：

```text
app/src/main/java/com/hirain/aiagent/core/component/SafetyGuard.java
app/src/main/java/com/hirain/aiagent/core/SafetyVerdict.java
app/src/main/java/com/hirain/aiagent/core/safety/AllowAllSafetyGuard.java
app/src/main/java/com/hirain/aiagent/core/safety/CompositeSafetyGuard.java
app/src/main/java/com/hirain/aiagent/core/safety/SpeedBasedDoorLockGuard.java
app/src/main/java/com/hirain/aiagent/core/terminator/SafetyVetoTerminator.java
```

同时清理：

- `AgentConfig` 中的安全 Guard 列表；
- `AgentConfigFactory` 中的 Persona 级 Guard 装配；
- Orchestrator 中的 Guard 遍历和 veto 计数；
- `AgentLoopContext.lastSafetyVeto`；
- 生产代码、测试和 Trace 中的 `SafetyVerdict` 类型引用；
- 旧注释中的 `SafetyGuard`、`VETO`、`5 km/h` 等过时描述。

不保留适配器或废弃别名，避免新旧体系同时存在。

---

## 11. `set_vehicle_spd` 删除方案

用户已确认删除模型可调用的 `set_vehicle_spd` Tool。

### 11.1 删除对外 Tool 能力

修改 `VehicleSpeedManager.java`：

- 删除 `@Tool(name = "set_vehicle_spd", ...)` 方法；
- 删除不再使用的 `@Tool`、`@P` 导入；
- 保留 `getSpeedStatus()`，因为当前车辆状态上下文仍在使用它。

修改 `AIAgentService.kt`：

- `VehicleSpeedManager` 继续用于状态采集；
- 从 `toolRegistry.registerAll(...)` 中移除 `speedManager`，因为它不再包含任何 Tool。

修改 `ToolGroupRegistry.java`：

- `CHASSIS_GROUP` 只保留 `set_chassis_mode`；
- 删除“包含车速控制”的过时注释。

### 11.2 保留 Demo 状态设置能力

保留：

- `VehicleStateMachine.setVehicleSpd(int)`；
- `SpeedState.setVehicleSpd(int)`。

它们不再暴露给 LLM，但仍用于 Demo 初始化、测试和模拟车辆运行状态。安全规则测试需要通过它们构造静止与行驶场景。

### 11.3 测试同步

- 更新 `ToolGroupRegistryTest`，断言 CHASSIS/COMMON 中不再出现 `set_vehicle_spd`；
- 更新 Tool 总数或工具组数量相关断言；
- 增加 ToolRegistry 验证，确认模型 ToolSpecification 中不存在 `set_vehicle_spd`。

历史总结、旧评估和旧计划文档中的 `set_vehicle_spd` 可作为历史记录保留；README 和当前架构说明必须更新为当前事实。

---

## 12. `MainAgentLoop` 删除方案

当前生产代码搜索结果显示：

- `MainAgentLoop` 仅在自身文件中定义；
- `AIAgentService`、其他生产类和测试没有构造或调用它；
- 它直接执行 Tool，不经过当前统一安全审核流程；
- 它内部还单独创建 `VehicleStateMachine` 和整套 Tool Manager，属于遗留的平行 AgentLoop。

因此实施时删除：

```text
app/src/main/java/com/hirain/aiagent/core/MainAgentLoop.java
```

删除前再执行一次全仓引用搜索；若仍然只有自身定义，则直接删除，不为其接入新安全引擎。

---

## 13. 文件级修改清单

### 13.1 新增生产文件

| 文件 | 作用 |
|---|---|
| `safety/ToolSafetyEngine.java` | 统一审核入口和规则调度 |
| `safety/SafetyCheckContext.java` | Tool 名称与标准化参数 |
| `safety/SafetyDecision.java` | ALLOW/DENY、原因码和原因 |
| `safety/SafetyRule.java` | 具体规则接口 |
| `safety/DefaultSafetyRules.java` | 固定 Tool → Rules 映射 |
| `safety/rules/DoorUnlockSafetyRule.java` | 静止时才允许解锁 |
| `safety/rules/ChassisModeSafetyRule.java` | 静止时才允许切换底盘模式 |

### 13.2 修改生产文件

| 文件 | 主要修改 |
|---|---|
| `VirtualStateMachine/VehicleStateMachine.java` | 增加类型明确的车速只读 getter |
| `core/AgentLoopOrchestrator.java` | 单 Engine 审核、删除 Guard 遍历和全部否决短路 |
| `core/TextAgentLoopOrchestrator.java` | 主 TEXT 路径接入单 Engine，并让 LLM 解释 DENY |
| `core/AgentConfig.java` | 删除 Persona 级 SafetyGuard 列表 |
| `core/factory/AgentConfigFactory.java` | 删除旧 Guard、SpeedManager 参数、parseSpeed 和 SafetyVetoTerminator |
| `core/AgentLoopContext.java` | 删除 lastSafetyVeto，记录 SafetyDecision |
| `core/ToolExecutionRecord.java` | SafetyVerdict 替换为 SafetyDecision |
| `trace/AgentTraceRecorder.java` | Trace 改为记录 Decision、ReasonCode、Reason |
| `trace/TraceAttributeKeys.java` | 删除 Guard/Veto 属性，增加 Safety Decision 属性 |
| `AIAgentService.kt` | 创建并共享 ToolSafetyEngine，更新各 Orchestrator 构造 |
| `tools/vehicle/speed/VehicleSpeedManager.java` | 删除 `set_vehicle_spd` Tool，保留状态查询 |
| `toolgroup/ToolGroupRegistry.java` | 从 CHASSIS_GROUP 删除 `set_vehicle_spd` |
| `README.md` | 更新 AgentLoop 管线、安全模块目录和规则说明 |

### 13.3 删除生产文件

| 文件 | 删除原因 |
|---|---|
| `core/component/SafetyGuard.java` | 被 `SafetyRule + ToolSafetyEngine` 取代 |
| `core/SafetyVerdict.java` | 被 `SafetyDecision` 取代 |
| `core/safety/AllowAllSafetyGuard.java` | 无规则默认 ALLOW 取代 |
| `core/safety/CompositeSafetyGuard.java` | Engine 内部规则 Map/List 取代 |
| `core/safety/SpeedBasedDoorLockGuard.java` | 被 `DoorUnlockSafetyRule` 取代 |
| `core/terminator/SafetyVetoTerminator.java` | DENY 后需要继续让 LLM 解释 |
| `core/MainAgentLoop.java` | 无调用方的遗留平行循环 |

### 13.4 测试文件

新增：

```text
app/src/test/java/com/hirain/aiagent/safety/ToolSafetyEngineTest.java
app/src/test/java/com/hirain/aiagent/safety/rules/DoorUnlockSafetyRuleTest.java
app/src/test/java/com/hirain/aiagent/safety/rules/ChassisModeSafetyRuleTest.java
```

修改至少包括：

- `core/TextAgentLoopOrchestratorTest.java`；
- `context/ContextCancellationAtomicityTest.java`；
- `runtime/ContextTextEndToEndTest.java`；
- `toolgroup/ToolGroupRegistryTest.java`；
- `trace/AgentTraceRecorderTest.java`；
- `trace/ToolPhaseTraceTest.java`；
- 其他因 `AgentConfig.safetyGuards(...)` 或 Orchestrator 构造参数变化而受影响的测试。

---

## 14. 分阶段实施计划

本任务拆分为五个阶段。每个阶段都必须满足以下要求：

1. 只处理该阶段列出的目标，不提前夹带后续清理；
2. 阶段内的生产代码、测试和必要注释一起完成；
3. 阶段结束后先执行阶段测试；
4. 阶段测试失败时先修复当前阶段，不直接进入下一阶段；
5. 每个阶段结束时记录修改文件、测试结果和遗留风险。

阶段关系：

```text
阶段一：建立新安全模块
        ↓
阶段二：接入 AgentLoop、记录和 Trace
        ↓
阶段三：删除旧 SafetyGuard 配置与实现
        ↓
阶段四：删除旧 Tool 和遗留 AgentLoop
        ↓
阶段五：文档更新与完整回归验收
```

阶段一结束时，新安全模块已经可以独立测试，但尚未接管真实 AgentLoop。阶段二结束时，新 Engine 成为唯一实际执行的安全审核路径；旧 SafetyGuard 文件只暂时保留用于维持编译，并在阶段三立即删除，不会出现新旧规则同时审核同一次 Tool 调用的情况。

---

### 14.1 阶段一：建立独立的 Tool Safety 核心模块

#### 阶段目标

完成新安全模块的数据结构、统一入口、规则映射、车辆状态读取和首批两条规则，使其能够脱离 AgentLoop 独立运行和测试。

本阶段不修改 AgentLoop，不删除旧 SafetyGuard，避免在核心规则尚未稳定前同时改动运行主链路。

#### Task 1.1：建立审核结果与审核上下文

新增：

- `safety/SafetyDecision.java`；
- `safety/SafetyCheckContext.java`。

具体任务：

1. 在 `SafetyDecision` 中定义 `ALLOW` 和 `DENY` 两种结果；
2. 在同一文件内定义稳定原因码，首批包含 `ALLOW`、`INVALID_ARGUMENT`、`SPEED_UNAVAILABLE`、`DOOR_UNLOCK_REQUIRES_STOPPED`、`CHASSIS_MODE_REQUIRES_STOPPED`、`RULE_EXECUTION_ERROR`；
3. 提供明确的 `allow()`、`deny(...)`、`isAllowed()`、`isDenied()` 等创建与判断方法；
4. 保证 `SafetyDecision` 创建后不可修改，ALLOW 不携带拒绝原因；
5. `SafetyCheckContext` 保存 Tool 名称和解析后的 Gson `JsonObject` 参数；
6. Context 不引入 `AgentLoopContext`、Persona、ChatMemory、Prompt 或 ToolGroup；
7. 为参数读取提供足够清晰的访问方式，但不建设通用参数转换框架。

#### Task 1.2：建立最小规则接口

新增：

- `safety/SafetyRule.java`。

具体任务：

1. 定义统一的 `check(SafetyCheckContext, VehicleStateMachine)` 方法；
2. 规则返回 `SafetyDecision`，不通过异常表达正常 DENY；
3. 在接口注释中明确规则只能读取车辆状态，不能修改车辆状态；
4. 不增加 `supports()`、优先级、规则名称、风险等级等第一版不需要的接口。

#### Task 1.3：补充车辆状态机只读能力

修改：

- `VirtualStateMachine/VehicleStateMachine.java`。

具体任务：

1. 增加类型明确的车速只读 getter，直接返回虚拟状态机中的当前车速；
2. 保留现有 `getSpeedStatus()`，避免影响 Context 中已有的车辆状态展示；
3. 不通过 `VehicleSpeedManager.getSpeedStatus()` 的中文 JSON 字符串读取安全状态；
4. 不新增 `SpeedProvider` 或独立状态快照；
5. 不一次性为八个车辆子系统增加全部 getter，后续规则需要什么状态再补什么只读入口。

#### Task 1.4：实现首批两条规则

新增：

- `safety/rules/DoorUnlockSafetyRule.java`；
- `safety/rules/ChassisModeSafetyRule.java`。

具体任务：

1. `DoorUnlockSafetyRule` 严格读取 boolean 类型的 `arg0`；
2. 锁门直接 ALLOW，解锁时读取车辆状态机车速；
3. 只有车速等于 0 才允许解锁，其他车速返回稳定 DENY；
4. `ChassisModeSafetyRule` 先确认 `arg0` 是 String，再读取当前车速；
5. 只有车速等于 0 才允许进入后续工具执行；
6. 底盘模式取值校验不写进规则，继续交给 `VehicleStateMachine.setChassisMode()`；
7. 车辆状态读取异常转换为 `SPEED_UNAVAILABLE`，不默认放行；
8. 中文原因应可直接供 LLM 理解，但程序判断只使用原因码。

#### Task 1.5：建立固定规则映射和统一 Engine

新增：

- `safety/DefaultSafetyRules.java`；
- `safety/ToolSafetyEngine.java`。

具体任务：

1. `DefaultSafetyRules` 显式创建 `Map<String, List<SafetyRule>>`；
2. 将 `set_door_lock` 映射到 `DoorUnlockSafetyRule`；
3. 将 `set_chassis_mode` 映射到 `ChassisModeSafetyRule`；
4. 保留同一 Tool 对应多条规则的能力，并使用固定 List 顺序；
5. Engine 保存规则 Map 的不可变副本，外部不能在运行中更换规则；
6. Engine 收到 Tool 请求后先按名称查找规则；无规则时不解析参数，直接 ALLOW；
7. 有规则时统一把 `request.arguments()` 解析为 Gson `JsonObject`；
8. JSON 无法解析时返回 `INVALID_ARGUMENT`；
9. 多条规则按顺序执行，首个 DENY 立即返回；
10. 规则未预期异常由 Engine 转换为 `RULE_EXECUTION_ERROR`；
11. 增加统一 DENY ToolResult 格式化能力，供后续三个 AgentLoop 路径复用。

#### Task 1.6：完成安全模块单元测试

新增：

- `safety/ToolSafetyEngineTest.java`；
- `safety/rules/DoorUnlockSafetyRuleTest.java`；
- `safety/rules/ChassisModeSafetyRuleTest.java`。

测试重点：

- 无规则 Tool 默认 ALLOW；
- 非法 JSON、参数缺失和参数类型错误稳定 DENY；
- 两条规则的静止、行驶、锁门、解锁分支；
- 底盘模式值不合法但车辆静止时，Safety 仍 ALLOW；
- 多规则固定顺序和首个 DENY 短路；
- 规则异常不会导致高风险 Tool 被放行；
- 规则 Map 不可被外部修改。

#### 阶段一测试与完成标准

建议测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.safety.*"
.\gradlew.bat :app:compileDebugJavaWithJavac
```

完成标准：

- 新模块七个生产文件和三类单元测试存在；
- 首批规则全部分支通过；
- 新模块不引用旧 `SafetyGuard`、`SafetyVerdict`；
- 旧 AgentLoop 行为尚未改变；
- Debug Java 编译通过后才进入阶段二。

---

### 14.2 阶段二：接入真实 AgentLoop、执行记录和 Trace

#### 阶段目标

让新 `ToolSafetyEngine` 接管真实 Tool 执行前审核，覆盖项目当前存在的全部三条 AgentLoop Tool 路径，并完成 DENY 写回 LLM、工具执行记录和 Trace 的语义迁移。

本阶段结束后，运行时不再调用旧 SafetyGuard；旧文件和 Persona 配置暂时保留到阶段三删除，但不能继续参与审核。

#### Task 2.1：在 Service 创建并共享唯一 Engine

修改：

- `AIAgentService.kt`；
- `core/AgentLoopOrchestrator.java`；
- `core/TextAgentLoopOrchestrator.java`。

具体任务：

1. 在 `AIAgentService` 中新增唯一 `ToolSafetyEngine` 字段；
2. 在唯一 `VehicleStateMachine` 初始化后，通过 `DefaultSafetyRules` 创建 Engine；
3. 将同一个 Engine 传给 chat `AgentLoopOrchestrator`；
4. 将同一个 Engine 传给主 TEXT `TextAgentLoopOrchestrator`；
5. 动态创建 scene Orchestrator 时继续传入同一个 Engine；
6. 两个 Orchestrator 的构造器将 Engine 设为必需依赖，避免 Tool 路径绕过审核；
7. 不在 Persona Factory 中重新创建 Engine 或规则实例。

#### Task 2.2：替换三处 Tool 执行前审核

修改：

- `core/AgentLoopOrchestrator.java`；
- `core/TextAgentLoopOrchestrator.java`。

必须覆盖：

1. `AgentLoopOrchestrator.execute(String, Map)` 遗留路径；
2. `AgentLoopOrchestrator.execute(RequestSession, ContextPrepareResult)` 兼容路径；
3. `TextAgentLoopOrchestrator` 当前主 TEXT 路径。

具体任务：

1. 删除每条路径中的 `for (SafetyGuard guard : ...)` 审核循环；
2. 每个 `ToolExecutionRequest` 只调用一次 `toolSafetyEngine.check(request)`；
3. ALLOW 时保持原有 ToolExecutor、真实 Dispatcher 和 DispatchDiagnostics 流程；
4. DENY 时跳过 ToolExecutor 和 Dispatcher，不产生车辆状态变更；
5. DENY 使用安全模块统一格式生成 ToolResult；
6. 每个 Tool 请求无论 ALLOW、DENY 或执行异常，都保持 Tool Result 配对；
7. 保持现有取消检查顺序，不借本阶段重构取消机制；
8. 多 Tool 场景逐个审核，某个 Tool 被拒绝不影响其他 Tool 继续完成各自审核和执行。

#### Task 2.3：让 DENY 回到 LLM 自然解释

修改：

- 两个 Orchestrator 中现有全部否决处理逻辑。

具体任务：

1. 删除三条路径中的 `vetoCount`；
2. 删除“全部工具均被安全否决”判断；
3. 删除固定输出“安全原因已阻止所有工具调用。”；
4. 将 DENY 结果作为 `ToolExecutionResultMessage` 写回当前 ChatMemory；
5. DENY 结果包含原因码、中文原因和“不要再次调用、向用户解释”的提示；
6. 完成当前 Tool 结果写回后正常 `continue` 到下一轮模型调用；
7. 保留最大迭代次数作为模型重复 Tool 调用的兜底，不新增第二套解释模型流程。

#### Task 2.4：迁移工具执行记录

修改：

- `core/ToolExecutionRecord.java`；
- `core/AgentLoopContext.java`；
- 两个 Orchestrator 的 `addToolResult(...)` 调用。

具体任务：

1. `ToolExecutionRecord` 用 `SafetyDecision` 替换 `SafetyVerdict`；
2. getter 名称同步改为 `safetyDecision()`；
3. `AgentLoopContext.addToolResult(...)` 接收并保存 `SafetyDecision`；
4. ALLOW 和 DENY 都记录实际 Decision；
5. Tool 执行异常仍允许 Decision 或结果按当前错误语义记录，不扩大异常模型改造；
6. 本阶段暂时保留 `lastSafetyVeto` 字段，仅用于让旧类继续编译，但新 AgentLoop 不再写入或读取它；该字段在阶段三随旧 Terminator 删除。

#### Task 2.5：迁移安全审核 Trace

修改：

- `trace/AgentTraceRecorder.java`；
- `trace/TraceAttributeKeys.java`；
- 相关 Trace 测试。

具体任务：

1. `finishTool(...)` 改为接收 `SafetyDecision`；
2. `finishToolSafetyCheck(...)` 不再接收 Guard 数量；
3. Trace 记录 ALLOW/DENY 决策、原因码和拒绝原因；
4. 删除或停用 `TOOL_SAFETY_GUARD_COUNT`；
5. 将 VETO 命名迁移为 DENY/DECISION 命名；
6. DENY 时 dispatch span 不应被创建或标记为真实执行成功；
7. Tool 异常与 Safety DENY 继续保持不同语义，避免把规则拒绝记录成系统异常。

#### Task 2.6：补充 AgentLoop 集成测试

修改或新增：

- `core/TextAgentLoopOrchestratorTest.java`；
- AgentLoop 兼容路径相关测试；
- `trace/AgentTraceRecorderTest.java`；
- `trace/ToolPhaseTraceTest.java`。

测试重点：

- DENY 时 ToolExecutor/Dispatcher 从未被调用；
- DENY 后车辆状态没有变化；
- DENY ToolResult 与请求 id/name 正确配对；
- 下一轮模型能够读取 DENY 结果并返回自然语言解释；
- 不再产生 AgentLoop 固定通用拒绝文本；
- 普通无规则 Tool 正常执行；
- 一轮多个 Tool 中部分 DENY、部分 ALLOW 时结果完整；
- 三条 AgentLoop 路径具有一致安全行为；
- Trace 中能看到 Decision 和 ReasonCode。

#### 阶段二测试与完成标准

建议测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*TextAgentLoopOrchestratorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*AgentTraceRecorderTest" --tests "*ToolPhaseTraceTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.safety.*"
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

完成标准：

- 三条真实 Tool 路径均调用统一 Engine；
- 运行时不存在旧 Guard 与新 Engine 双重审核；
- DENY 能进入下一轮 LLM 并得到自然解释；
- ToolExecutionRecord 和 Trace 使用 `SafetyDecision`；
- chat、TEXT、scene 使用同一个 Engine 实例；
- 相关集成测试和 Debug 编译通过后才进入阶段三。

---

### 14.3 阶段三：删除旧 SafetyGuard 配置和实现

#### 阶段目标

在新 Engine 已经稳定接管运行时后，删除旧 SafetyGuard 的配置入口、Persona 装配、终止器和全部实现，完成真正的单体系收敛。

#### Task 3.1：从 AgentConfig 删除 Persona 级安全配置

修改：

- `core/AgentConfig.java`；
- 所有构建 `AgentConfig` 的生产代码和测试。

具体任务：

1. 删除 `List<SafetyGuard> safetyGuards` 字段；
2. 删除构造赋值、getter、Builder 默认值和 Builder 方法；
3. 删除相关 import；
4. 更新测试中的 `.safetyGuards(List.of())` 和自定义 VetoSafetyGuard；
5. 安全测试改为向 Orchestrator 注入真实或测试用 `ToolSafetyEngine`，不再模拟 Guard 列表。

#### Task 3.2：清理 AgentConfigFactory 旧装配

修改：

- `core/factory/AgentConfigFactory.java`；
- `AIAgentService.kt` 中对应 factory 调用。

具体任务：

1. 删除 `AllowAllSafetyGuard`、`SpeedBasedDoorLockGuard`、`SafetyVetoTerminator` 等 import；
2. 删除 chat、scene、TEXT、vision Persona 中所有 `.safetyGuards(...)`；
3. 删除仅为旧 Guard 服务的 `VehicleSpeedManager speedManager` factory 参数；
4. 删除 `parseSpeed(...)` 及中文 JSON 车速解析；
5. 从 CompositeTerminator 中移除 `SafetyVetoTerminator`，保留原有正常终止器；
6. 确认 Persona Factory 不再决定安全规则内容。

#### Task 3.3：删除旧状态和旧文件

修改或删除：

- `core/AgentLoopContext.java`；
- 旧 SafetyGuard 相关六个生产文件。

具体任务：

1. 删除 `lastSafetyVeto` 字段、getter 和 setter；
2. 删除 `SafetyGuard.java`；
3. 删除 `SafetyVerdict.java`；
4. 删除 `AllowAllSafetyGuard.java`；
5. 删除 `CompositeSafetyGuard.java`；
6. 删除 `SpeedBasedDoorLockGuard.java` 及内部 `SpeedProvider`；
7. 删除 `SafetyVetoTerminator.java`；
8. 清理生产代码和测试中的旧 import、旧变量名和 `VETO` 注释；
9. 确认不存在为旧体系保留的适配器或空壳类。

#### Task 3.4：更新受影响的 Context 与 Runtime 测试

至少检查：

- `context/ContextCancellationAtomicityTest.java`；
- `runtime/ContextTextEndToEndTest.java`；
- 其他使用 `AgentConfig.builder(...)` 或 Orchestrator 构造器的测试。

具体任务：

1. 删除测试中的旧 SafetyGuard 配置；
2. 为需要运行 Tool 的 Orchestrator 注入可控 Engine；
3. 无安全规则场景使用空规则 Map 的 Engine，验证默认 ALLOW；
4. 需要 DENY 的场景使用真实规则和可设置状态的 `VehicleStateMachine`；
5. 保持原有 Context、取消、会话隔离测试目标不变。

#### 阶段三测试与完成标准

建议测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ContextCancellationAtomicityTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*ContextTextEndToEndTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*TextAgentLoopOrchestratorTest"
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

同时执行源码搜索，生产代码和测试中不得再出现：

```text
SafetyGuard
SafetyVerdict
SafetyVetoTerminator
SpeedBasedDoorLockGuard
lastSafetyVeto
safetyGuards(
```

完成标准：

- 旧体系文件全部删除；
- AgentConfig 不再承载安全规则；
- AgentConfigFactory 不再按 Persona 装配安全策略；
- Context、Runtime、AgentLoop 定向测试通过；
- Debug 编译通过后才进入阶段四。

---

### 14.4 阶段四：删除 `set_vehicle_spd` Tool 和遗留 `MainAgentLoop`

#### 阶段目标

移除已经确认不应由 LLM 调用的车速设置 Tool，并删除完全未使用且绕过统一安全引擎的遗留 AgentLoop，收敛 Tool 和运行入口。

#### Task 4.1：删除模型可调用的车速设置 Tool

修改：

- `tools/vehicle/speed/VehicleSpeedManager.java`；
- `AIAgentService.kt`。

具体任务：

1. 删除 `setVehicleSpd(...)` 的 `@Tool` 方法；
2. 删除不再使用的 `@Tool`、`@P` import；
3. 保留 `VehicleSpeedManager.getSpeedStatus()` 供现有车辆状态上下文采集；
4. 从 `toolRegistry.registerAll(...)` 删除不再含 Tool 的 `speedManager`；
5. 保留 `VehicleStateMachine.setVehicleSpd(int)` 和 `SpeedState.setVehicleSpd(int)`，供 Demo 和测试构造车辆状态；
6. 确认 ToolSpecification 数量随 Tool 删除正确减少。

#### Task 4.2：更新 ToolGroup 注册和测试

修改：

- `toolgroup/ToolGroupRegistry.java`；
- `toolgroup/ToolGroupRegistryTest.java`；
- 其他断言 Tool 数量或 Tool 名称的测试。

具体任务：

1. `CHASSIS_GROUP` 只保留 `set_chassis_mode`；
2. 删除“车速控制归入底盘组”的过时注释；
3. 更新 COMMON、CHASSIS 和全量 Tool 数量断言；
4. 增加明确断言，确保任何可供模型使用的 ToolSpecification 中都没有 `set_vehicle_spd`；
5. 不删除车辆速度状态本身，也不增加 `CruiseSpeedSafetyRule`。

#### Task 4.3：再次确认并删除 `MainAgentLoop`

修改：

- 删除 `core/MainAgentLoop.java`。

具体任务：

1. 删除前再次执行全仓引用搜索；
2. 确认除自身定义外无构造、字段、反射类名或测试引用；
3. 确认 `AIAgentService` 实际使用 `AgentRuntime`、`TextAgentLoopOrchestrator` 和 `AgentLoopOrchestrator`；
4. 删除整个 `MainAgentLoop.java`，不尝试给死代码接入新 Engine；
5. 重新编译，利用编译器确认没有隐藏调用方。

#### 阶段四测试与完成标准

建议测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolGroupRegistryTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.safety.*"
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

同时执行源码与运行时注册检查：

- 生产 Tool 注解中不存在 `set_vehicle_spd`；
- ToolGroup 中不存在 `set_vehicle_spd`；
- `VehicleStateMachine.setVehicleSpd()` 仍存在且测试可调用；
- `MainAgentLoop` 文件和引用全部消失。

完成标准：

- 模型无法再调用车速设置 Tool；
- Demo 仍可人为设置车速测试安全规则；
- 旧平行 AgentLoop 已删除；
- ToolGroup 和安全规则测试通过；
- Debug 编译通过后才进入阶段五。

---

### 14.5 阶段五：文档更新、完整回归与最终验收

#### 阶段目标

确保代码、测试、README、架构说明和实际运行行为完全一致，并完成本次较大改造的最终回归。

#### Task 5.1：更新当前有效文档

修改：

- `README.md`；
- `docs/overview/safety-guard-module-current-state.md` 或新增对应完成总结文档。

具体任务：

1. README 的 AgentLoop 管线将 `SafetyGuard` 更新为 `ToolSafetyEngine`；
2. README 目录树加入 `safety/` 及核心文件职责；
3. 删除 README 中 `set_vehicle_spd` 是可调用 Tool 的过时描述；
4. 说明无规则 Tool 默认 ALLOW、高风险状态不可用时 DENY；
5. 说明车门解锁和底盘模式都要求车速为 0；
6. 说明 DENY 结果回写 LLM，由 LLM 生成自然语言解释；
7. 将现状文档明确标注为“改造前历史现状”，或新增改造完成后的模块总结，避免读者把旧 Guard 设计当作当前实现；
8. 历史阶段总结和旧计划不批量重写，只保证当前入口文档准确。

#### Task 5.2：执行全量自动化测试

按以下顺序执行：

1. Safety Engine 与两条规则单元测试；
2. AgentLoop 和 LLM DENY 解释集成测试；
3. Context、Runtime 和取消相关回归测试；
4. ToolGroup 与 ToolRegistry 测试；
5. Trace 测试；
6. 完整 Debug 单元测试；
7. Debug APK 构建。

建议命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

#### Task 5.3：执行最终静态核验

具体任务：

1. 搜索旧 SafetyGuard 类型和 VETO 术语残留；
2. 搜索 `set_vehicle_spd` 在生产代码、Prompt、ToolGroup 和当前文档中的残留；
3. 搜索 `MainAgentLoop` 引用；
4. 核对 `DefaultSafetyRules` 中只有已确认规则，没有假想规则；
5. 核对三个 AgentLoop Tool 路径都在 dispatch 前调用 Engine；
6. 核对无规则 Tool 默认放行，DENY Tool 不进入 dispatch；
7. 核对各 Persona/场景复用同一 Engine；
8. 核对工作区中用户原有的无关修改没有被覆盖或纳入本任务改动。

#### Task 5.4：执行 Demo 行为验收

至少人工验证以下对话：

1. 车辆静止时请求解锁，Tool 允许执行；
2. 车辆行驶时请求解锁，Tool 不执行，LLM 自然说明需要停车；
3. 车辆静止时切换底盘模式，合法模式执行成功；
4. 车辆行驶时切换底盘模式，Tool 不执行，LLM 自然说明需要停车；
5. 静止时传入非法底盘模式，由 `VehicleStateMachine` 返回参数错误，而不是 Safety 伪造安全拒绝；
6. 调用天气、空调等无专用规则 Tool，保持默认正常执行；
7. 尝试诱导模型调用 `set_vehicle_spd`，模型工具列表中不存在该 Tool；
8. 检查 Trace，DENY 事件包含 Decision、ReasonCode 和 Reason。

#### 阶段五完成标准

- 第 16 节全部验收标准满足；
- 完整 Debug 单元测试通过；
- Debug APK 构建通过，或明确记录与本次代码无关的环境阻塞；
- README 和当前架构文档与代码一致；
- 最终总结按“工作目标、修改内容与逻辑、工作总结”三部分输出；
- 总结中逐阶段列出测试结果和仍存在的 Demo 限制。

---

## 15. 测试计划

本节作为跨阶段的完整测试矩阵，用于最终回归和防止覆盖遗漏；具体执行顺序及每个阶段的准入条件以第 14 节对应的“阶段测试与完成标准”为准。

### 15.1 `ToolSafetyEngine` 单元测试

- 无规则 Tool 默认 ALLOW；
- 有规则 Tool 的合法 JSON 能进入对应规则；
- 有规则 Tool 的非法 JSON 返回 `INVALID_ARGUMENT`；
- 一个 Tool 多条规则按声明顺序执行；
- 第一条 DENY 后后续规则不再执行；
- 所有规则 ALLOW 时最终 ALLOW；
- 规则抛出未预期异常时返回 `RULE_EXECUTION_ERROR`；
- Engine 初始化后规则 Map 不可被外部修改。

### 15.2 车门解锁规则测试

| 场景 | 预期 |
|---|---|
| 非 boolean 或缺少 `arg0` | DENY / INVALID_ARGUMENT |
| 车速 70，`arg0=true` 锁门 | ALLOW |
| 车速 0，`arg0=false` 解锁 | ALLOW |
| 车速 1，`arg0=false` 解锁 | DENY / DOOR_UNLOCK_REQUIRES_STOPPED |
| 车速 70，`arg0=false` 解锁 | DENY / DOOR_UNLOCK_REQUIRES_STOPPED |
| 车速读取异常 | DENY / SPEED_UNAVAILABLE |

### 15.3 底盘模式规则测试

| 场景 | 预期 |
|---|---|
| 非 String 或缺少 `arg0` | DENY / INVALID_ARGUMENT |
| 车速 0，合法模式 | ALLOW |
| 车速 70，合法模式 | DENY / CHASSIS_MODE_REQUIRES_STOPPED |
| 车速 0，无效模式字符串 | Safety ALLOW，随后由 VehicleStateMachine 返回参数错误 |
| 车速读取异常 | DENY / SPEED_UNAVAILABLE |

### 15.4 AgentLoop 集成测试

- DENY 时 ToolExecutor 调用次数为 0；
- DENY 仍写入与 ToolRequest 匹配的 `ToolExecutionResultMessage`；
- DENY ToolResult 包含稳定原因码和中文原因；
- 全部 Tool DENY 后不会返回固定文本“安全原因已阻止所有工具调用。”；
- 模型下一轮可以读取 DENY 结果并返回自然语言解释；
- 普通无规则 Tool 仍正常执行；
- 同一轮部分 Tool DENY、部分 ALLOW 时，两者均写回正确结果；
- TEXT 主路径和 AgentLoop 遗留/兼容路径行为一致；
- 取消发生时仍保持现有 ToolExchange 闭合行为。

### 15.5 工具与清理测试

- ToolRegistry 不再暴露 `set_vehicle_spd`；
- CHASSIS_GROUP 不再包含 `set_vehicle_spd`；
- `VehicleStateMachine.setVehicleSpd()` 仍能为测试设置车速；
- 生产与测试代码中不存在 `SafetyGuard`、`SafetyVerdict`、`SafetyVetoTerminator` 引用；
- 不存在 `new MainAgentLoop(...)` 或 `MainAgentLoop` 类；
- 所有 Persona 使用同一个 Engine 实例，不再创建 Persona 专属规则。

### 15.6 建议验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

如完整测试耗时较长，应先运行安全模块和 AgentLoop 相关定向测试，再运行完整 Debug 单元测试。构建失败时需区分本次代码错误与本地 Android/Gradle 环境问题。

---

## 16. 验收标准

满足以下条件后，才可认为 SafetyGuard 替换完成：

1. LLM 产生 Tool 调用后，真实 ToolExecutor 之前必经 `ToolSafetyEngine.check()`；
2. AgentLoop 中不存在遍历多个 SafetyGuard 的代码；
3. 无专用规则的 Tool 默认执行；
4. 行驶状态下解锁车门被拒绝，静止状态下允许；
5. 行驶状态下切换底盘模式被拒绝，静止状态下允许；
6. 关键参数错误、车速不可用或规则异常时不会默认放行高风险 Tool；
7. DENY Tool 不执行，但 DENY 结果正确写回 ChatMemory；
8. DENY 后由 LLM 输出自然语言解释，不再由 AgentLoop 返回固定通用文本；
9. 所有 Persona 共用同一 Engine 和规则 Map；
10. `set_vehicle_spd` 不再出现在 ToolSpecification、ToolRegistry 或 ToolGroup 中；
11. `VehicleStateMachine.setVehicleSpd()` 仍可供 Demo 和测试设置车辆状态；
12. 旧 SafetyGuard、SafetyVerdict、SafetyVetoTerminator 和 MainAgentLoop 文件已删除；
13. ToolExecutionRecord 和 Trace 能记录 ALLOW/DENY、原因码和原因；
14. 安全模块、AgentLoop、ToolGroup、Trace 相关测试全部通过；
15. README 和当前架构说明与新实现一致。

---

## 17. 实施注意事项

- 当前工作区已有与本计划无关的 Prompt 和其他文档改动，实施时不得覆盖或回滚这些用户修改。
- `ToolExecutionRequest.arguments()` 与当前 `ToolDispatcher` 一致，第一版按 `arg0`、`arg1` 读取，不额外建设参数名称映射框架。
- 对无规则 Tool，应先判断 Map 是否存在对应规则，再解析参数，避免低风险 Tool 因安全模块不认识其参数而被误拒绝。
- 规则必须确定性执行，不调用 LLM、不访问网络、不依赖 Persona 或 ChatMemory。
- DENY 中文原因应简洁、稳定、可直接供 LLM 转述；原因码一旦进入测试和 Trace，不应随意改名。
- 新增代码注释统一使用中文，并重点说明安全边界和设计原因。
- 如果后续规则扩充到十几条，继续使用 `DefaultSafetyRules + Map<String, List<SafetyRule>>` 即可；只有实际出现维护困难时，才重新评估是否需要更复杂机制。
