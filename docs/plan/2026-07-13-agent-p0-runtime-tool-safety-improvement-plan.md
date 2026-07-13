# AIAgent 三项 P0：请求控制、工具收敛与车控安全改进计划（自审修订版）

> 编写日期：2026-07-13  
> 自审修订：2026-07-13  
> 当前状态：Safety 基础模块已实现；P0-1、P0-2 与文本二次确认尚未实施  
> 剩余阶段：3 个阶段，每阶段独立测试和验收  
> 来源：`docs/overview/agent-architecture-and-runtime-flow-evaluation.md` 的三个 P0 结论

---

## 1. 自审结论

本轮依据当前生产代码、Safety 单元测试和实际运行路径重新审核了原计划。结论是：**三项 P0 的方向成立，但原计划不能原样执行**。

| 审核项 | 原计划问题 | 修订结论 |
|---|---|---|
| Safety 现状 | 将 Safety 当作尚未建立，计划重复创建 Engine、规则、接线和旧代码清理 | 将现有 Safety 作为已完成基线，只增量补充确认能力 |
| 请求准入时点 | 使用 `RequestSession` 注册，意味着第二请求已先进入 Runtime 路由 | 准入前移到 `AgentRuntime.startSession()` 之前 |
| 取消后的槽位释放 | 原计划把“发送取消/超时响应”和“释放执行名额”视为同一步 | 两者分离；旧执行真正退出后才释放名额 |
| HTTP 取消方案 | 计划引入拦截器，但 OkHttp interceptor 无法直接取得所属 `Call` 做登记 | 在自定义 HTTP adapter 的 `execute()` 内直接登记、限时和注销 `Call` |
| Safety 覆盖范围 | 要求 46 个 Tool 全部建立显式 Policy，改动过大且推翻现有默认语义 | 只对具体 HIGH 风险 Tool 强制规则覆盖；LOW/MEDIUM 保持现有默认 ALLOW |
| Safety 类型结构 | 计划新增 `SafetyDecisionType`、`SafetyReasonCode`、PolicyRegistry，与已实现代码冲突 | 延用现有 `SafetyDecision` 内部枚举、`SafetyRule` 和 `DefaultSafetyRules` |
| 多 Tool 确认 | 原计划未充分约束“前面的低风险 Tool 已执行，后面才遇到确认” | Tool 批次先整体预检；出现确认时整批不执行 |
| 非 TEXT 路径 | 原计划要求统一 Engine，但没有说明无法文本确认的路径如何处理 | 所有路径都禁止绕过；非 TEXT 遇到确认要求时安全拒绝，不创建 PendingAction |

### 1.1 已确认完成的 Safety 基线

当前代码已经完成：

- 建立 `safety/ToolSafetyEngine` 作为统一执行前审核入口；
- 建立 `SafetyDecision`、`SafetyRule`、`SafetyCheckContext` 和 `DefaultSafetyRules`；
- 实现 `DoorUnlockSafetyRule` 与 `ChassisModeSafetyRule`；
- 参数、车速读取和规则执行异常均采用 DENY；
- 同一个 Engine 已注入 TEXT、兼容 AgentLoop 和 scene/legacy 三条 Tool 路径；
- 旧 `SafetyGuard`、`SafetyVerdict`、旧 Guard 实现和 `SafetyVetoTerminator` 已删除；
- `MainAgentLoop` 已删除；
- `set_vehicle_spd` 已从模型 Tool 集合移除，但 `VehicleStateMachine.setVehicleSpd()` 仍保留给 Demo 和测试；
- Safety Trace 与相关回归测试已迁移。

本轮重新执行完整 JVM 测试后共 **305 项，0 failure，0 error，0 skipped**。这证明现有 Safety 基线可用，但不代表下面三个剩余阶段已经完成。

### 1.2 Safety 仍未完成的能力

现有 Safety 决策只有 `ALLOW / DENY`，静止状态下的解锁和底盘切换会直接 ALLOW。以下能力仍缺失：

- `REQUIRE_CONFIRMATION` 决策；
- 待确认动作保存、过期、取消和原子消费；
- 用户通过普通 TEXT 回复“确认执行”；
- 确认时重新读取车辆状态；
- 多 Tool 批次避免部分执行；
- 非 TEXT 路径对确认型动作的安全关闭。

因此，**Safety 模块已经实现，但 P0-3 的“高风险文本二次确认”尚未完成**。

---

## 2. 工作目标

本计划处理三个 P0 的剩余工作：

1. **P0-1 请求控制**：单 Session 下，同一时刻只允许一个 TEXT 请求运行；统一 30 秒端到端 deadline；取消和超时能够停止同步模型 HTTP Call。
2. **P0-2 ToolGroup 收敛**：删除 UNKNOWN、null、异常和弱关键词路径自动扩大 Tool 集合的 fail-open 行为。
3. **P0-3 Safety 增量增强**：保留现有 Safety 实现，在其上增加高风险文本确认和确认前状态复核。

目标 TEXT 主路径：

```text
普通 AIDL TEXT 请求
    → Runtime 之前原子准入
    → 单一 30 秒 deadline
    → IntentRouter / ToolGroupSelector
    → 不确定或异常时不暴露 Tool
    → Context / AgentLoop / 模型调用
    → Tool 批次安全预检
        ├─ DENY：写回拒绝结果，不 dispatch
        ├─ ALLOW：正常 dispatch
        └─ REQUIRE_CONFIRMATION：整批不 dispatch，返回确认文本

下一条普通 AIDL TEXT 请求“确认执行”
    → 同一请求门禁与 30 秒 deadline
    → 原子领取原始动作
    → 重新读取实时状态并复核
    → 只执行保存的原始 Tool 与参数一次
```

完成目标是“真实车辆接入前的本地安全基础”，不是本轮直接接入真实 SOA，也不表示满足量产功能安全要求。

---

## 3. 已确认决定与工作边界

### 3.1 已确认决定

- 只考虑一个 Session。
- 同一时刻只接受一个 TEXT Agent 请求，不建立排队。
- 第二个请求立即通过现有 Listener 返回忙碌文本。
- 统一使用 30 秒端到端 deadline。
- 不修改 AIDL 方法、Parcelable、SDK 对外签名、TestApp 协议和 Manifest 权限。
- 二次确认通过下一条普通 TEXT 请求完成。
- 第一版只接受严格文本 `确认执行` 和 `取消执行`，不交给 LLM 判断。
- 确认时执行服务内保存的原始 Tool 和规范化参数，不让 LLM 重新生成。
- PendingAction TTL 固定 30 秒，只保存在内存中。

### 3.2 本次必须完成

- TEXT 请求在 Runtime 前完成单槽位原子准入。
- deadline、取消状态和 HTTP Call 贯穿 Service、Runtime、Context、AgentLoop 与模型 adapter。
- cancel / timeout / success / failure 只有一个对外终态响应。
- 请求执行体真正退出前不释放单槽位。
- ToolGroup 所有生产 fallback 改为 fail-closed 或纯聊天。
- Context 只使用明确选中的 ToolSpecification，不自行扩权。
- 在现有 Safety 上增加确认决策、PendingAction、严格文本解析和确认复核。
- 所有 Tool 路径遇到 REQUIRE_CONFIRMATION 时都不得 dispatch。
- 更新测试、README、Safety overview 和评估报告完成状态。

### 3.3 本次明确不做

- 不支持多 Session、多用户并发、优先级队列或新请求抢占。
- 不统一 IMAGE / VOICE / CONTROL 的完整生命周期。
- 不修改通信机制，不增加确认专用 AIDL 或 confirmation token 字段。
- 不增加调用方鉴权、Manifest 权限或白名单。
- 不接入真实车辆 SOA，不实现 ECU 回执、事务补偿和物理状态闭环。
- 不建立规则 DSL、配置中心、数据库策略或动态加载。
- 不增加 Safety Agent 或第二个 LLM 审核器。
- 不引入新依赖，不调整 Gradle、AGP 或 LangChain4j 版本。
- 不重构 Memory、Context、Trace 或 Persona 的无关代码。

### 3.4 本地安全基础的责任边界

本计划完成后仍需未来独立处理：

- 真实车辆状态的时效性、来源可信性和读取失败语义；
- SOA 指令是否被接收、执行或部分执行；
- 执行后物理状态确认；
- 命令幂等、重放保护和失败补偿；
- 量产调用方鉴权和系统权限。

---

## 4. 剩余阶段安排

| 阶段 | 工作内容 | 状态 |
|---|---|---|
| Safety 基线 | Engine、两条 HIGH 风险规则、三路径接线、旧 Guard 清理、车速 Tool 移除 | 已完成，不重复实施 |
| 阶段一 | 单请求准入、统一 30 秒 deadline、HTTP 取消、终态与槽位分离 | 待实施 |
| 阶段二 | ToolGroup fail-closed 与最小 Tool 暴露 | 待实施 |
| 阶段三 | 基于现有 Safety 增加文本确认和状态复核 | 待实施 |

执行顺序为阶段一 → 阶段二 → 阶段三。阶段三依赖阶段一提供可靠准入、deadline 和取消语义；阶段二可先独立编码，但合并验收仍放在阶段一之后。

---

## 5. 阶段一：单请求门禁与统一 30 秒 deadline

### 5.1 当前事实

- TEXT 在 `AgentRuntime.startSession()` 后才调用 `ActiveRequestRegistry.register()`。
- `register()` 使用 `put()`，会覆盖相同 requestId。
- TEXT 在单个 `HandlerThread` 上执行，第二请求会排队。
- Service timeout、AgentLoop timeout 和 HTTP read timeout 分别为 15、30、120 秒。
- cancel / timeout 抢占终态后会很快 `finish()`；旧 worker 和 HTTP Call 可能仍未退出。
- 当前取消检查主要抑制迟到响应，不能直接取消同步 OkHttp `Call`。

### 5.2 Runtime 前置原子准入

建议新增或修改：

- `runtime/RequestAdmission.java`：保存 requestId、原始 Session 标识、开始时间与 deadline；
- `runtime/RequestAdmissionResult.java`：`ACCEPTED / BUSY / DUPLICATE_ACTIVE / DUPLICATE_FINISHED`；
- `runtime/ActiveRequest.java`；
- `runtime/ActiveRequestRegistry.java`；
- `AIAgentService.kt`。

执行顺序必须调整为：

```text
规范化 requestId / userId / personaId
    → ActiveRequestRegistry.tryAcquire(RequestAdmission)
        ├─ 拒绝：直接返回 BUSY 或 DUPLICATE
        └─ 接受：创建 TraceSession
                  → AgentRuntime.startSession(..., deadline)
                  → 提交 TEXT worker
```

约束：

1. `tryAcquire()` 必须在 `AgentRuntime.startSession()`、IntentRouter 和 ToolGroupSelector 之前执行。
2. Registry 使用单一原子槽位，不以 `ConcurrentHashMap.isEmpty() + put()` 拼接非原子判断。
3. 重复 requestId 不覆盖 active 请求，也不删除 finished 缓存。
4. BUSY / DUPLICATE 继续使用现有 `AgentResponse` 和 Listener；不增加 AIDL 字段。
5. 为避免 IMAGE、VOICE 或会话 CRUD 占用现有 `mWorkHandler` 导致已接受 TEXT 排队，TEXT 使用独立单 worker 执行器或独立 HandlerThread；本阶段不改其他输入类型的生命周期。
6. worker 提交失败必须抢占 FAILED、清理资源并释放槽位。

### 5.3 终态通知与执行槽位分离

这是阶段一的核心约束：

- **终态通知**：哪个事件赢得 `RUNNING → COMPLETED/CANCELLED/TIMEOUT/FAILED` 的 CAS，哪个事件负责发送唯一响应。
- **执行槽位释放**：只有 worker 已停止、当前 HTTP Call 已注销、Trace scope 已关闭后，才能释放槽位。

因此：

1. cancel / timeout 可以立即发送唯一终态响应并调用 `Call.cancel()`。
2. cancel / timeout handler 不得立即让新请求获得执行槽位。
3. worker 在 `finally` 中完成 Call 注销、Trace 关闭和 Registry release。
4. 如果同步 Tool 已经进入 dispatch 且不能中断，槽位必须保持 BUSY 直到 Tool 返回。
5. finished requestId 缓存仍保留 60 秒，用于阻止短时间重放。
6. `finish()` 需改名或拆分，避免同时承担“记录终态”和“释放槽位”两种含义。

### 5.4 单一 RequestDeadline

新增 `runtime/RequestDeadline.java`，只保存和计算：

- `startedAtMs`；
- `deadlineAtMs = startedAtMs + 30_000`；
- `remainingMs(now)`；
- `isExpired(now)`。

deadline 在 `tryAcquire()` 成功时创建，并随 RequestAdmission 传入 RequestSession。检查点：

- Runtime / Context 开始前；
- 每次模型调用前和返回后；
- Tool 批次 Safety 预检前；
- 每个 Tool dispatch 紧邻之前；
- 确认动作领取后和 dispatch 前。

TEXT 不再把 `AgentConfig.timeout()` 当作第二个独立时钟。Service timeout 使用 deadline 剩余时间调度。超时统一映射为 `TIMEOUT`，用户取消映射为 `CANCELLED`。

### 5.5 同步模型 HTTP Call 取消

不新增 OkHttp interceptor。直接修改自定义 `langchain4j/http_client_ok/OkHttpClient.execute()`：

1. 从 `RequestExecutionContext` 获取 requestId、deadline 和当前终态。
2. 创建 `Call` 后，用 `call.timeout()` 将本次调用限制在剩余 deadline 内。
3. 在 `RequestCallRegistry` 登记 requestId → Call。
4. 同步执行 `call.execute()`。
5. 在 `finally` 中按 Call 实例条件注销，避免前一轮模型调用误删后一轮登记。
6. cancel / timeout 赢得终态后调用 `RequestCallRegistry.cancel(requestId)`。
7. `IOException` 根据当前终态映射为 CANCELLED 或 TIMEOUT；其他情况保留网络失败语义。
8. worker finally 清理 ThreadLocal，避免后续复用线程继承旧 requestId。

一个 AgentLoop 可能串行发起多次模型调用，Registry 只保存当前正在执行的 Call，不持久化，不写入 Context 或 Memory。

### 5.6 阶段一测试与完成标准

必测场景：

- 第二个 TEXT 请求在 Runtime/IntentRouter 调用次数为 0，并立即 BUSY；
- 相同 active requestId 和 finished requestId 均不能重放；
- 30 秒从准入成功时开始，不从 worker 真正运行时开始；
- cancel、timeout、success 竞态只发送一个响应；
- cancel/timeout 后、旧 worker 退出前，新请求仍为 BUSY；
- worker finally 后下一请求才可进入；
- cancel 和 timeout 都调用当前 `Call.cancel()`；
- 多轮模型调用按 Call 实例正确登记和注销；
- cancelled IOException 与 timeout IOException 映射正确；
- deadline 到期后不开始新的 Tool dispatch；
- RequestExecutionContext 异常后清理。

建议验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ActiveRequestRegistryTest" --tests "*AgentRuntimeTest" --tests "*RuntimeResponseMapperTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*TextAgentLoopOrchestratorTest" --tests "*OkHttpClient*Test"
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

阶段完成标准：同一时刻只有一个已接受且未退出的 TEXT 执行体；所有 TEXT 层共享一个 30 秒 deadline；取消/超时可取消当前模型 Call；对外只有一个终态响应。

---

## 6. 阶段二：ToolGroup fail-closed 与最小工具暴露

### 6.1 当前事实

- Selector null 和异常会在 `AgentRuntime` 中调用 `allToolsFallback()`。
- null intent 和部分 UNKNOWN 会选择 `ALL_SAFE_DEMO_GROUP`。
- CHAT / UNKNOWN 的弱车载关键词可能扩大到 `COMMON_VEHICLE_GROUP`。
- `ToolGroupContextProvider` 已实际将 selectedToolNames 转为模型可见 ToolSpecification，因此 fallback 会真实扩大模型能力。
- Provider 仍通过 `selectionReason` 字符串判断 CHAT_ONLY，控制语义不够稳定。

### 6.2 显式选择状态

在 `ToolGroupSelectionResult` 中增加稳定状态，不再从 reason 字符串推断：

```text
SELECTED                 明确业务意图，只暴露对应 ToolGroup
CHAT_ONLY                普通聊天，不暴露 Tool，仍调用 LLM
CLARIFICATION_REQUIRED   表达含糊，不暴露 Tool，返回确定性澄清文本
FAILED_CLOSED            Selector/Registry 内部失败，不暴露 Tool，不调用 LLM
```

保持现有 `ToolGroupSelector.select(IntentResult, String)` 两参数 SAM 签名，避免破坏测试和调用方。可调整 Result 工厂，但不改接口形状。

### 6.3 Selector 收敛规则

- 明确普通聊天 → CHAT_ONLY。
- 明确 AC / WINDOW / SEAT / DOOR / CHASSIS 等意图 → SELECTED 对应业务组。
- UNKNOWN + 非车载文本 → CHAT_ONLY。
- UNKNOWN + 弱车载关键词 → CLARIFICATION_REQUIRED。
- CHAT 中仅出现弱车载词但没有明确动作 → CLARIFICATION_REQUIRED，不选择 COMMON_VEHICLE_GROUP。
- null intent → FAILED_CLOSED。
- `ALL_SAFE_DEMO_GROUP` 可保留给显式测试，但默认 Selector 和生产 Runtime 不得选择。

示例澄清文本：

```text
请明确要控制空调、车窗、座椅、车门还是底盘。
```

### 6.4 Runtime 与 Context 行为

`AgentRuntime`：

- selector 返回 null → `FAILED_CLOSED / TOOL_GROUP_SELECTOR_NULL`；
- selector 抛异常 → `FAILED_CLOSED / TOOL_GROUP_SELECTOR_EXCEPTION`；
- 选择结果与 Registry 不一致 → `FAILED_CLOSED / TOOL_GROUP_SELECTION_INVALID`；
- CHAT_ONLY → 正常 Context 和 LLM，但 ToolSpecification 为空；
- CLARIFICATION_REQUIRED → 直接返回确定性澄清文本；
- FAILED_CLOSED → 返回稳定系统错误，不调用 Context、LLM 或 Tool；
- SELECTED → 正常主链。

`ToolGroupContextProvider`：

- 使用 selection status，不再比较 `selectionReason`；
- 只有 SELECTED 才解析 ToolSpecification；
- 其他状态固定 MODE_NONE；
- 空选择不补全全量 Tool；
- ToolRegistry 已具备缺失 Tool 抛异常能力，继续作为 required provider 的失败关闭边界。

### 6.5 阶段二测试与完成标准

必测场景：

- null intent、selector null、selector exception 均无模型调用和 Tool；
- UNKNOWN 普通文本为 CHAT_ONLY；
- UNKNOWN / CHAT 弱车载表达要求澄清；
- 生产 Runtime 不再调用 `allToolsFallback()`；
- 明确业务意图只暴露对应 ToolGroup；
- CHAT_ONLY 的 `ChatRequest.tools()` 为空；
- Context 不自行扩权；
- Trace 区分四种稳定状态。

建议验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*DefaultToolGroupSelectorTest" --tests "*ToolGroupRegistryTest" --tests "*ToolGroupContextProviderTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*AgentRuntimeTest" --tests "*AgentRuntimeToolGroupTraceTest" --tests "*ContextTextEndToEndTest"
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac
```

阶段完成标准：生产代码不存在“路由越不确定、模型可见 Tool 越多”的路径；普通聊天可用；明确车控只获得最小工具集合。

---

## 7. 阶段三：在现有 Safety 上增加文本二次确认

### 7.1 不重复实施的内容

本阶段不得重新创建 Safety 模块，不得恢复旧 Guard，也不新增平行的 PolicyRegistry。以下现有类型继续作为唯一基础：

```text
safety/ToolSafetyEngine.java
safety/SafetyDecision.java
safety/SafetyCheckContext.java
safety/SafetyRule.java
safety/DefaultSafetyRules.java
safety/rules/DoorUnlockSafetyRule.java
safety/rules/ChassisModeSafetyRule.java
```

现有三个 AgentLoop Tool 接入点只做增量适配，不重新接线。

### 7.2 扩展现有安全决策

直接扩展 `SafetyDecision.DecisionType`：

```text
ALLOW
DENY
REQUIRE_CONFIRMATION
```

在现有 `ReasonCode` 中增补确认相关原因码，不拆出重复枚举文件：

```text
HIGH_RISK_CONFIRMATION_REQUIRED
CONFIRMATION_EXPIRED
CONFIRMATION_CANCELLED
CONFIRMATION_STATE_CHANGED
CONFIRMATION_ALREADY_CONSUMED
CONFIRMATION_CHANNEL_UNAVAILABLE
MULTI_TOOL_CONFIRMATION_NOT_SUPPORTED
POLICY_NOT_CONFIGURED
```

新增轻量 `SafetyCheckMode`：

- `INITIAL`：静止状态下的解锁和底盘切换返回 REQUIRE_CONFIRMATION；
- `CONFIRMED_RECHECK`：同样条件仍满足时返回 ALLOW，条件变化则 DENY。

保留 `check(ToolExecutionRequest)` 作为 INITIAL 的兼容入口；新增显式 recheck 方法或 mode 参数。不得用外部 Boolean 直接绕过 Safety。

### 7.3 策略覆盖边界

不要求为当前全部 46 个 Tool 建立空壳 Policy。修订后的覆盖规则是：

1. 具体业务组标记为 HIGH 的 Tool 必须在 `DefaultSafetyRules` 中存在专用规则。
2. 当前具体 HIGH 工具只有 `set_door_lock` 和 `set_chassis_mode`，二者已存在规则。
3. COMMON / ALL_SAFE 等聚合组的 HIGH 只表示其中包含高风险 Tool，不把所有成员误判为 HIGH。
4. HIGH Tool 缺规则时运行时 DENY / POLICY_NOT_CONFIGURED，并由覆盖测试阻止回归。
5. LOW / MEDIUM Tool 保持当前“无专用规则默认 ALLOW”，继续由 `VehicleStateMachine` 做参数校验和状态收敛。
6. ToolGroup riskLevel 只用于覆盖校验和 Trace，不能替代参数级 SafetyRule。

该边界既避免推翻已实现 Safety，也保证新增高风险 Tool 不会因漏配规则直接放行。

### 7.4 首批确认语义

`set_door_lock`：

- `arg0=true` 锁门 → ALLOW；
- `arg0=false` 且车速不为 0 / 车速不可读 / 参数异常 → DENY；
- `arg0=false`、车速为 0、INITIAL → REQUIRE_CONFIRMATION；
- CONFIRMED_RECHECK 时车速仍为 0 → ALLOW，否则 DENY。

`set_chassis_mode`：

- 参数异常、车速不为 0 或不可读 → DENY；
- 车速为 0、INITIAL → REQUIRE_CONFIRMATION；
- CONFIRMED_RECHECK 时车速仍为 0 → ALLOW，否则 DENY；
- 模式枚举合法性仍由 `VehicleStateMachine` 负责，不复制业务枚举。

### 7.5 Tool 批次预检

模型一次可能返回多个 Tool request。AgentLoop 必须先对整批请求完成 Safety 预检并缓存决策，再开始任何 dispatch：

- 任一请求需要确认 → 本阶段整批不执行，且仅允许该批次恰好包含一个 Tool；
- 多 Tool + REQUIRE_CONFIRMATION → `MULTI_TOOL_CONFIRMATION_NOT_SUPPORTED`，要求用户拆分；
- 预检过程中每个 Tool 只调用一次 INITIAL Safety check；
- 不允许先执行前面的低风险 Tool，再因后面的高风险 Tool 中止。

DENY 是否阻止同批其他互不相关 Tool，保持现有行为；本阶段只强制确认型批次“零部分执行”。

### 7.6 PendingToolAction 与严格文本解析

新增轻量确认子模块，可放在 `safety/confirmation/`：

```text
PendingToolAction.java
PendingToolActionStore.java
ConfirmationTextParser.java
ToolConfirmationCoordinator.java
```

PendingAction 至少保存：confirmationId、sessionId、originalRequestId、原始 Tool request id、toolName、规范化不可变 arguments、动作摘要、createdAtMs、expiresAtMs 和原子状态。

约束：

- 单 Session 最多一个 PendingAction；
- TTL 30 秒，只存内存，Service 重启自动失效；
- `takeForConfirmation()` 使用 CAS，动作最多领取一次；
- 确认请求不能替换 toolName 或 arguments；
- 新的普通请求先取消旧 PendingAction，再作为普通 Agent 请求处理；
- 只接受 trim 后完全等于 `确认执行` 或 `取消执行`；
- “好”“可以”“继续”等模糊表达不得消费动作。

### 7.7 首轮确认响应

INITIAL 返回 REQUIRE_CONFIRMATION 后：

1. 不调用 ToolDispatcher。
2. 将原 AiMessage 的 Tool request 配对写入 `[CONFIRMATION_REQUIRED]` ToolResult，保证 ToolExchange 完整。
3. 在创建 PendingAction 成功后终止本轮 AgentLoop，不再让模型生成第二次 Tool。
4. 写入并返回确定性 Assistant 文本，例如：

```text
即将执行：车辆静止状态下解锁车门。
该操作需要二次确认，请在 30 秒内明确回复“确认执行”；如不执行，请回复“取消执行”。
```

5. 不新增 AIDL 状态；confirmationId 只用于内部 Trace 和防重放。
6. 如果请求已取消或 deadline 已到期，不得创建 PendingAction。

### 7.8 确认请求执行

确认文本在 IntentRouter 和 LLM 之前识别，但仍先经过阶段一的请求门禁和 30 秒 deadline：

1. 原子领取 PendingAction 并检查 TTL。
2. 校验 sessionId；单 Session 仍保留该检查，防止未来扩展后误用。
3. 用保存的原 Tool 与参数调用 `CONFIRMED_RECHECK`。
4. recheck ALLOW 且请求仍未取消、deadline 未到期时，才调用 ToolDispatcher。
5. 通过实际 ToolResult 生成确定性文本；当前 VirtualStateMachine 环境不得描述为真实物理车辆已经完成动作。
6. 无论执行成功、DENY、超时或异常，PendingAction 都不可再次执行。
7. ChatMemory 只追加普通 User / Assistant 消息，不写没有原 Tool request 的孤立 ToolResult。

### 7.9 非 TEXT 路径

所有现有 Tool 路径仍必须尊重 REQUIRE_CONFIRMATION：

- TEXT 主路径可以创建 PendingAction 并返回确认文本；
- VOICE、scene 或 legacy 路径本阶段不建立确认状态，返回 `CONFIRMATION_CHANNEL_UNAVAILABLE` 并跳过 dispatch；
- 不允许任何非 TEXT 路径把 REQUIRE_CONFIRMATION 当作 ALLOW。

### 7.10 阶段三测试与完成标准

在现有 Safety 测试上增量补充：

- 当前两项 HIGH Tool 均有规则，新增 HIGH Tool 漏配时测试失败且运行时 DENY；
- 静止解锁/底盘 INITIAL 返回 REQUIRE_CONFIRMATION；
- 行驶、状态不可读、参数或规则异常仍 DENY；
- CONFIRMED_RECHECK 只有状态仍满足才 ALLOW；
- 首轮不 dispatch，严格确认只执行原动作一次；
- 取消、过期、重复和并发确认均不重复执行；
- 确认前由静止变为行驶时不执行；
- 多 Tool 确认型批次零部分执行；
- 非 TEXT 确认型动作不执行；
- ToolResult 与 request id 配对，Memory 无孤立 ToolResult；
- timeout / cancel 后不创建 PendingAction；
- 三条 AgentLoop Tool 路径没有安全旁路。

建议验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.hirain.aiagent.safety.*"
.\gradlew.bat :app:testDebugUnitTest --tests "*TextAgentLoopOrchestratorTest" --tests "*AgentLoopOrchestrator*Test" --tests "*ContextTextEndToEndTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*AgentTraceRecorderTest" --tests "*ToolPhaseTraceTest" --tests "*ToolGroupRegistryTest"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

阶段完成标准：所有路径都无法绕过现有 Safety；HIGH Tool 首轮不执行；普通 TEXT 确认无需改通信接口；确认前复核实时状态；同一动作最多执行一次。

---

## 8. 文件影响矩阵

| 范围 | 预计修改/新增 | 边界 |
|---|---|---|
| Service | `AIAgentService.kt` | 内部准入、TEXT executor、timeout、确认分流；不改 Binder 方法 |
| Runtime | ActiveRequest、Admission、Deadline、RequestSession、RuntimeResult/Mapper | 前置准入、终态与槽位分离 |
| HTTP adapter | 自定义 `OkHttpClient.java`、RequestExecutionContext、RequestCallRegistry | 同步模型 Call 限时和取消，不引入 interceptor |
| ToolGroup | Selector、SelectionResult/Status、AgentRuntime、Context Provider | 移除生产全量 fallback，保持 Selector SAM |
| Safety | 现有 Decision/Engine/两条 Rule，新增 confirmation 子模块 | 增量扩展，不重建 Safety |
| AgentLoop | 两个 Orchestrator 的批次预检和确认分支 | 保留现有统一 Engine 接线 |
| Trace | request、deadline、ToolGroup status、confirmation 属性 | 不记录完整 Tool 参数 |
| 测试 | runtime、HTTP、toolgroup、context、core、safety、trace | 每阶段定向，最终全量 |
| 文档 | README、Safety overview、评估报告 | 更新当前状态，不批量改历史文档 |

明确不修改：

```text
app/src/main/aidl/**
AgentRequest.java 的 Parcelable 字段
AgentResponse.java 的 Parcelable 字段
AIAgent.java 的对外方法签名
TestApp 通信协议
AndroidManifest.xml 权限
Gradle 与依赖版本
```

---

## 9. 最终验收场景

### 9.1 请求与超时

1. 正常聊天在 30 秒内返回。
2. 请求运行中再次提交，第二请求在 Runtime 前立即 BUSY。
3. 取消当前请求，只收到取消响应，模型 Call 被 cancel；旧 worker 退出前仍拒绝新请求。
4. 超过 deadline，只收到 TIMEOUT，后续不开始新的 Tool。
5. 相同 requestId 不覆盖、不重放。

### 9.2 ToolGroup

1. “打开空调”只暴露 AC 工具。
2. 普通聊天不暴露 Tool，但仍可由 LLM 回答。
3. “帮我调一下车”只返回澄清文本。
4. Selector null / exception 时 Context、LLM 和 Tool 调用次数均为 0。

### 9.3 Safety 与确认

1. 车速 0 请求解锁：不执行，返回确认文本。
2. 回复“确认执行”：重新读取车速，执行保存的动作一次。
3. 回复“取消执行”：不执行。
4. 回复“好的”：不确认旧动作，旧动作取消后按普通请求处理。
5. 确认前车速从 0 变为 10 km/h：拒绝执行。
6. 超过 30 秒、重复确认或并发确认：不会重复执行。
7. 行驶中解锁或切换底盘：直接 DENY，不进入确认。
8. 多 Tool 响应包含确认型动作：整批零执行并要求拆分。
9. 非 TEXT 路径产生确认型动作：不执行。
10. `set_vehicle_spd` 始终不在模型 Tool 列表中。

---

## 10. 最终验收标准

只有同时满足以下条件，三个 P0 才算完成：

1. Runtime 前单槽位准入，第二 TEXT 请求不排队；
2. 全链使用同一个 30 秒 deadline；
3. cancel / timeout 能取消当前同步模型 Call；
4. 对外终态唯一，执行体退出前槽位不释放；
5. requestId 不覆盖、不重放；
6. UNKNOWN / null / exception 不再暴露全量 Tool；
7. 明确车控只暴露最小 ToolGroup；
8. 现有 Safety Engine 仍是所有 Tool 路径的唯一审核入口；
9. HIGH Tool 缺少专用规则时 fail-closed；
10. 高风险动作首轮不执行，确认只使用原始 Tool 与参数；
11. 确认前实时状态复核，动作最多执行一次；
12. AIDL、SDK、TestApp 和 Manifest 通信边界未改变；
13. 定向测试、完整 JVM 测试和 `assembleDebug` 通过；
14. README、Safety overview 和评估报告与代码一致；
15. 最终总结明确说明真实 SOA、物理回执和调用方鉴权仍未完成。

---

## 11. 实施纪律与停止条件

- 严格按阶段一 → 阶段二 → 阶段三实施。
- 每阶段先运行定向测试，失败不得进入下一阶段。
- 不重复实现或替换当前 Safety 模块。
- 不覆盖工作区已有 Prompt、README、Context、Trace 或其他未提交修改。
- 新增注释使用中文，解释失败关闭、deadline 和原子状态的设计原因。
- 不格式化无关文件，不引入新依赖。
- 若实现需要修改 AIDL、Parcelable、SDK、TestApp 协议或 Manifest，立即停止并询问用户。
- 若发现新的具体 HIGH Tool、Tool 风险等级有业务歧义，列出 Tool 名、当前行为和候选策略后询问用户，不擅自分类。
- 若 OkHttp 同步调用之外还存在本阶段必须覆盖的异步模型调用，先报告实际调用路径，再决定是否扩展 CallRegistry。
- 不以编译通过代替语义验收；必须人工核对 BUSY、deadline、fail-closed 和确认后只执行一次。
