# Tool Safety Policy Engine 模块现状

> 最后更新：2026-07-13 | 依据当前生产代码与单元测试

## 一、模块定位

`ToolSafetyEngine` 是所有 AgentLoop 在执行 Tool 之前使用的统一安全审核入口。它只接收 LangChain4j 生成的工具调用请求，并使用以下三类信息完成确定性判断：

- 工具名称
- 标准化后的 JSON 参数
- 规则按需读取的 `VehicleStateMachine` 车辆状态

模块不依赖完整的 `AgentLoopContext`，不随 Persona 改变，也不负责工具执行后的参数校验或状态收敛。后两项职责仍由 `VehicleStateMachine` 承担。

```text
LLM ToolCall
  → ToolSafetyEngine.check()
      → 低/中风险且没有专用规则：ALLOW
      → 具体 HIGH Tool 缺少专用规则：DENY / POLICY_NOT_CONFIGURED
      → 有专用规则：按固定顺序检查车辆状态与参数
          → ALLOW：ToolExecutor → VehicleStateMachine
          → DENY：不执行 Tool → 拒绝 ToolResult 写回 LLM → LLM 自然解释
          → REQUIRE_CONFIRMATION：整批不执行 → 保存原始动作 → 普通 TEXT 确认后复核
```

## 二、目录与文件职责

```text
safety/
├── ToolSafetyEngine.java       # 唯一审核入口、规则选择、异常收口、拒绝结果格式化
├── SafetyCheckContext.java     # 工具名 + 参数 + INITIAL / CONFIRMED_RECHECK
├── SafetyCheckMode.java        # 初次审核与确认后复核
├── SafetyDecision.java         # ALLOW / DENY / REQUIRE_CONFIRMATION
├── SafetyRule.java             # 单条业务规则接口
├── DefaultSafetyRules.java     # 规则映射 + 具体 HIGH Tool 清单
├── confirmation/               # 单 Session、30 秒、原子消费的文本确认状态机
└── rules/
    ├── DoorUnlockSafetyRule.java  # 解锁必须静止
    └── ChassisModeSafetyRule.java # 切换底盘模式必须静止
```

各文件边界如下：

| 文件 | 负责什么 | 不负责什么 |
|------|----------|------------|
| `ToolSafetyEngine` | 找到工具对应规则、解析标准参数、按序执行规则、统一处理规则异常 | 不写具体车控条件，不执行 Tool |
| `SafetyCheckContext` | 保存一次审核需要的最小输入 | 不读取车辆状态 |
| `SafetyDecision` | 表达稳定的 ALLOW / DENY / REQUIRE_CONFIRMATION 结果 | 不决定业务条件 |
| `SafetyRule` | 约束所有具体规则的统一调用方式 | 不维护规则注册关系 |
| `DefaultSafetyRules` | 集中说明哪个 Tool 使用哪些规则 | 不动态加载配置、不引入规则 DSL |
| `rules/*` | 读取必要状态并完成单一业务判断 | 不接触 AgentLoop、Persona 或 ToolExecutor |

## 三、规则映射设计

当前使用 `Map<String, List<SafetyRule>>`，而不是让 AgentLoop 遍历所有规则：

```text
set_door_lock   → DoorUnlockSafetyRule
set_chassis_mode → ChassisModeSafetyRule
```

这种方式对 Demo 足够轻量，同时允许未来扩充到十余条规则：新增规则类后，只需在 `DefaultSafetyRules` 增加明确映射。一个工具也可以按固定顺序绑定多条规则，Engine 遇到第一条 DENY 后立即停止。

未注册专用规则的低风险 Tool 默认 ALLOW。当前具体 HIGH Tool 为 `set_door_lock` 和 `set_chassis_mode`，缺少专用规则时 Engine 以 `POLICY_NOT_CONFIGURED` 失败关闭；聚合 ToolGroup 的 HIGH 不会误扩散到全部成员。

为避免 Map 配置错误造成静默放行，Engine 构造时会拒绝空规则列表、null 规则、空工具名和带首尾空格的工具名。`VehicleDoorManager`、`VehicleChassisManager` 的 Tool 注解与规则 Map 共用同一组编译期名称常量，并通过契约测试确认 LangChain4j 生成的参数 schema 仍包含规则读取的 `arg0`。

## 四、当前显式安全规则

### 4.1 车门解锁

工具：`set_door_lock`

- `arg0=true` 表示上锁，安全规则直接 ALLOW
- `arg0=false` 表示解锁：行驶中 DENY；静止且 INITIAL 时 REQUIRE_CONFIRMATION；确认复核时仍静止才 ALLOW
- 关键参数缺失、类型错误或车速无法获得时 DENY
- 车辆运动时原因码为 `DOOR_UNLOCK_REQUIRES_STOPPED`

### 4.2 底盘模式切换

工具：`set_chassis_mode`

- 行驶中 DENY；静止且 INITIAL 时 REQUIRE_CONFIRMATION；确认复核时仍静止才 ALLOW
- 规则与主驾驶、副驾驶等座舱位置无关
- 关键参数缺失、类型错误或车速无法获得时 DENY
- 车辆运动时原因码为 `CHASSIS_MODE_REQUIRES_STOPPED`

## 五、审核结果与失败策略

当前结果包括 `ALLOW`、`DENY` 和 `REQUIRE_CONFIRMATION`。确认不是外部布尔值放行，而是使用保存的原始 Tool 与参数再次进入同一个 Engine。

当前原因码：

| 原因码 | 含义 |
|--------|------|
| `ALLOW` | 允许执行 |
| `INVALID_ARGUMENT` | 审核所需工具参数无效或不完整 |
| `SPEED_UNAVAILABLE` | 高风险判断需要车速，但当前无法取得 |
| `DOOR_UNLOCK_REQUIRES_STOPPED` | 车辆未静止，拒绝解锁 |
| `CHASSIS_MODE_REQUIRES_STOPPED` | 车辆未静止，拒绝切换底盘模式 |
| `RULE_EXECUTION_ERROR` | 规则返回异常结果或执行抛出异常 |
| `HIGH_RISK_CONFIRMATION_REQUIRED` | 高风险动作首轮需要文本二次确认 |
| `CONFIRMATION_EXPIRED / CANCELLED / ALREADY_CONSUMED` | PendingAction 已失效，不能执行 |
| `CONFIRMATION_STATE_CHANGED` | 确认前车辆状态已不满足执行条件 |
| `CONFIRMATION_CHANNEL_UNAVAILABLE` | 非 TEXT 路径不能完成本阶段确认 |
| `MULTI_TOOL_CONFIRMATION_NOT_SUPPORTED` | 确认型动作与其他 Tool 同批出现 |
| `POLICY_NOT_CONFIGURED` | 具体 HIGH Tool 缺少专用规则 |

有专用规则的高风险 Tool 采用保守失败策略：关键参数、必要车辆状态或规则执行结果不可用时 DENY。拒绝文本统一格式为：

```text
[SAFETY_DENY][原因码]
中文拒绝原因。请向用户说明拒绝原因，不要再次调用该工具。
```

AgentLoop 将其作为 ToolResult 写回模型，由下一轮模型自然说明原因；不会直接把固定模板作为最终用户回复。

## 六、运行时接入

`AIAgentService` 在创建唯一的 `VehicleStateMachine` 后创建一个共享 `ToolSafetyEngine`，并将同一实例注入：

- TEXT 使用的 `TextAgentLoopOrchestrator`
- VOICE 等兼容链路使用的 `AgentLoopOrchestrator`
- 场景响应动态创建的 `AgentLoopOrchestrator`

当前三个 Tool 执行路径都先完成整批审核。DENY 会跳过对应 Tool；ALLOW 才进入 ToolRegistry / ToolDispatcher；任一 Tool 需要确认时整批零执行。TEXT 保存单个 PendingAction，VOICE、scene 与 legacy 路径返回确认通道不可用。

Trace 使用 `tool.safety.decision`、`tool.safety.reason_code` 和 `tool.safety.reason` 记录审核结论。安全拒绝属于预期业务结果，不被标记为规则运行故障；安全 ALLOW 也不再等同于工具执行成功，TEXT 主链会继续结合 `DispatchDiagnostics` 决定 `tool.dispatch_success` 和外层 `tool.success`。

## 七、文本二次确认闭环

- 不修改 AIDL、Parcelable 或 TestApp 协议；下一条普通 TEXT 请求即确认通道。
- 仅接受 trim 后完全等于“确认执行”或“取消执行”，模糊表达会取消旧 PendingAction 后按普通请求处理。
- 单 Session 最多一个 PendingAction，仅存内存，TTL 固定 30 秒。
- PendingAction 保存原始 requestId、Tool request id、toolName、不可变 arguments、动作摘要与原子状态。
- 确认时原子领取，重新读取车辆状态，最多 dispatch 一次；过期、取消、状态变化、重复或并发确认均不执行。
- 最终文本明确说明结果来自虚拟车辆状态机，不把 Demo 状态描述成真实车辆物理回执。

## 八、与 VehicleStateMachine 的边界

安全模块通过 `VehicleStateMachine.getVehicleSpd()` 读取规则需要的车速。随着规则增加，可以直接按需读取状态机已有的空调、车门、车窗、座椅、底盘、香氛和 DMS 等状态，不需要为每一种状态建立单独 Provider 文件。

虚拟车辆默认以 0 km/h 启动，使“静止时要求确认、确认复核仍静止才允许”的业务分支在删除模型调速 Tool 后仍然可以实际到达。`SpeedState` 的车速字段使用 `volatile`，保证状态采集线程更新后 Agent 工作线程能够读取到最新值；Demo 阶段不额外引入复杂锁或状态快照框架。

边界保持不变：

- ToolSafetyEngine：执行前业务安全判断
- ToolExecutor / ToolRegistry：工具定位和执行
- VehicleStateMachine：执行阶段参数校验、虚拟状态变更与状态收敛

## 九、本次替换结果

旧 SafetyGuard 体系已经从生产路径删除，包括 `SafetyGuard`、`SafetyVerdict`、三个旧 Guard 实现和 `SafetyVetoTerminator`。`AgentConfig` 不再保存 Persona 级安全规则列表，因此安全规则天然由所有 Persona 共用。

同时完成以下范围收敛：

- 删除模型工具 `set_vehicle_spd`，避免 LLM 直接修改用于安全判断的车辆速度
- 保留 `VehicleStateMachine.setVehicleSpd()`，用于 Demo 状态模拟和测试准备
- 删除没有外部引用的旧 `MainAgentLoop`
- ToolGroup 全量工具数由 47 调整为 46

## 十、验证覆盖

2026-07-13 完整 JVM 回归共 339 项，0 failure，0 error；`:app:assembleDebug` 构建成功。

当前单元测试覆盖：

- 未注册工具默认放行
- 非法规则 Map 在 Engine 创建时立即失败，不会退化为默认放行
- 安全规则名称与真实 `@Tool` 名称、LangChain4j `arg0` schema 保持一致
- 多规则固定顺序与首个 DENY 短路
- 无效 JSON、空规则结果和规则异常的稳定拒绝
- 解锁在静止、行驶、参数异常和车速不可用时的行为
- 底盘模式在静止、行驶、参数异常和车速不可用时的行为
- TEXT 链路 DENY 后不执行 Tool、拒绝结果写回记忆、LLM 进入下一轮自然解释
- Trace 安全审核属性和 DENY 业务语义
- 虚拟车辆默认静止时两个受限操作要求确认，确认复核仍静止才放行
- PendingAction 创建、取消、过期、状态变化、重复和并发确认最多执行一次
- 多 Tool 确认批次零部分执行，非 TEXT 确认型动作不执行
- 具体 HIGH Tool 缺少规则时运行时失败关闭

该实现定位为“真实车辆接入前的本地安全基础”：规则显式、行为可测、扩展路径清晰，但不包含真实状态来源可信性、SOA 回执、执行后物理状态确认、调用方鉴权、命令幂等与失败补偿，也不等同于量产功能安全认证。
