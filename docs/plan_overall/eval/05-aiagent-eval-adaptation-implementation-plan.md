# AIAgent Eval 适配详细实现计划

> **文档性质：** 供子 Agent 在 Goal 模式中逐 Phase 执行的详细计划。
> **目标项目：** `D:\code\android\AndroidStudioProjects\AIAgent`
> **对应大纲：** `01-aiagent-eval-adaptation-design-outline.md`
> **协作计划：** `04-aiagent-eval-pc-implementation-plan.md`、`06-testapp-eval-bridge-implementation-plan.md`
> **计划目标：** 只为现有 AIAgent 增加 Debug/Test 适配能力，不在 APK 内实现 Dataset、Grader、Judge 或 Report。

> **实施状态（2026-07-17）：** AIAgent 侧 Phase 1–3 代码、JVM 测试、Debug/Release 构建和 Manifest 隔离已完成。Phase 4 中依赖 TestApp 与电脑端项目的 Fixture、真实 Binder/Phoenix/安全确认设备验收尚未关闭；不得将其标记为已验收。

## 0. Goal 窗口交接与当前工作树

本计划位于目标项目内：

```text
D:\code\android\AndroidStudioProjects\AIAgent\docs\plan_overall\eval\05-aiagent-eval-adaptation-implementation-plan.md
```

新窗口首次只创建 Phase 1 Goal，目标写为“严格完成本计划 Phase 1 并通过 Phase 1 完成门”；每个 Phase 完成并汇报后，再创建下一 Phase Goal。执行者必须先完整读取本计划、`AGENTS.md`、电脑端计划和 TestApp 计划，不得仅凭聊天摘要实现。

跨项目硬依赖：AIAgent Phase 1 开始前，电脑端计划 Phase 1 的 protocolVersion、command、result、vehicle-state Schema 必须已经落盘；AIAgent Phase 1–3 可在 TestApp 尚未改造时独立完成。AIAgent Phase 4 的 Binder/设备闭环必须等待 TestApp 至少完成其 Phase 3。依赖未满足时标记“等待前置 Phase”，不得自行创建临时协议或伪客户端绕过。

2026-07-16 最终审计时，AIAgent 工作树已经包含用户的 README、AGENTS/CLAUDE、overview/plan 文档修改和多个未跟踪文件，本计划目录本身也未跟踪。执行前必须重新运行 `git status --short`，保留全部既有修改；Phase 4 更新 README/Trace 文档时只能在现有内容上合并，禁止覆盖、回退或格式化无关段落。若待修改代码文件出现新的重叠修改，停止并向用户确认。当前 `adb` 未加入 PowerShell PATH，但 `local.properties` 指向的 SDK 中存在 `D:\code\android\forSdk\Sdk\platform-tools\adb.exe`；设备命令应先解析 `sdk.dir` 或使用该绝对路径，不要把“找不到 adb”误判为设备功能失败。

## 1. 工作目标

在保持现有 AIDL 主接口、TEXT Runtime、Context、AgentLoop、Tool、Safety、Memory 和 Trace 业务语义不变的前提下，为电脑端 `AIAgent_Eval` 和 Android `AIAgentTestApp` 提供以下能力：

1. Debug-only、独立于主业务 AIDL 的 Eval 控制入口。
2. 8 个虚拟车辆子系统的结构化快照、默认重置和原子状态 Patch。
3. 单 Eval 环境租约、调用方隔离、TTL 释放和环境修订号。
4. 普通请求与 Eval 请求并发时的确定性准入行为。
5. 每个 TEXT 请求在根 Trace 上稳定记录 `clientMessageId/correlationId`。
6. AgentResponse、状态快照和 Trace 各自保持独立事实边界。
7. Debug 版本指纹：APK、Prompt、模型、Trace 模式和状态 Schema 版本。
8. Release 构建中不存在可绑定的 Eval Debug Service。

## 2. 当前实现基线

### 2.1 主请求和取消链路

- 主业务 AIDL 为 `IAIAgentAidlInterface`，`processAgentRequest(AgentRequest)` 返回 `void`。
- TestApp 当前在发送前生成 `requestId`；AIAgent 的 `ensureRequestId()` 只在缺失时补 UUID。
- `cancelAgentRequest(requestId, reason)` 依赖 `ActiveRequestRegistry`、`RequestCallRegistry` 和 confirmation coordinator。
- 因此本计划不改变 requestId 所有权，不把电脑端生成的 correlationId 当成 requestId。

### 2.2 VehicleStateMachine

- AIAgentService 在 `onCreate()` 中只创建一个 `VehicleStateMachine`。
- Tool Manager、ToolSafetyEngine 和 Context 状态读取都引用该实例。
- 状态机持有 AC、Door、Window、Seat、Speed、Chassis、Frag、DMS 共 8 个状态对象。
- 当前只提供业务 setter 和中文 JSON 字符串 getter，没有结构化快照、原子 Patch、统一 reset 或 revision。
- 本计划必须复用该唯一实例，不创建 Eval 专用第二状态机。

### 2.3 Trace

- TEXT 根 span 是 `agent.request`，下游已有 Context、LLM、Tool、Safety、Dispatch、Memory 和 `response.dispatch`。
- `TraceAttributeKeys.CLIENT_MESSAGE_ID` 已存在，当前由 Runtime 路径写入；被准入层提前拒绝的请求可能无法稳定获得该属性。
- 二次确认已有 `safety.confirmation.id/status`。
- AgentResponse 才是调用方最终收到的文本与终态；Trace 不承担结果传输。

### 2.4 构建结构

- 当前只有 `src/main` 和 `src/test`，没有 `src/debug` 或 `src/release`。
- 主模块已启用 AIDL 和 BuildConfig。
- 本计划使用 `src/debug` 提供 Eval Binder Service，Release 不合并该组件。

## 3. 关键设计决策

### 3.1 不修改主业务 AIDL

保持下列文件及 ABI 不增加 Eval 方法：

- `app/src/main/aidl/com/hirain/aiagent/IAIAgentAidlInterface.aidl`
- `AgentRequest`
- `AgentResponse`
- `CancelRequestResult`

新增独立 Debug AIDL：

```text
com.hirain.aiagent.eval.IAIAgentEvalDebug
    String execute(String requestJson)
```

理由：避免重发主 SDK、避免 Release 暴露状态写接口，并让 TestApp 通过版本化 JSON 对齐电脑端 Schema。

### 3.2 Debug 接口和共享协议的关系

- `AIAgent_Eval/schemas` 是三方共享命令、结果和车辆状态 Schema 的唯一标准。
- 电脑端 Schema 只覆盖“电脑端 ↔ TestApp”的外层 EvalCommand/EvalResultEnvelope/VehicleStateSnapshot。
- AIAgent Debug Service 的 `EvalDebugRequest/EvalDebugResponse` 是“TestApp ↔ AIAgent”的 Android 内部传输契约，只实现环境控制语义，不是另一份电脑端 command/result Schema。
- AIAgent 与 TestApp 间的 `leaseToken` 是 Android 内部能力凭据，不是电脑端 correlation ID，也不进入 Dataset。
- Debug AIDL 使用 JSON 字符串，避免为 Debug 状态控制新增一组跨仓库 Parcelable；TestApp mapper 负责把内部 response 转成不含 token 的外层 operationResult。
- 两层协议共享 protocol major、operation 名和 VehicleStateSnapshot 字段；内部 token/lease expiry 字段不得进入外层 Schema。

### 3.3 调用方保护

Debug Service 仅存在于 Debug APK，并在每次 Binder 调用中验证：

- `Binder.getCallingUid()` 能解析到固定包名 `com.hirain.aiagent.test`。
- 该调用 App 带 `FLAG_DEBUGGABLE`。
- 请求 JSON 长度、协议版本和 operation 合法。

Demo 阶段不强制两个项目统一签名；如果未来要在非 Debug 环境开放，必须另立计划升级为 signature permission，不允许直接复用本期入口。

### 3.4 Eval 环境租约

- AIAgent 生成不可预测的 `leaseToken`，TestApp 只在 Android 内部保存。
- 租约所有权使用 TestApp calling UID，不新增电脑端 ID。
- Acquire 只在无活跃 Agent 请求和无正在执行的主动场景任务时成功。
- 有租约时，owner UID 的 Eval 请求可进入；其他 UID 请求返回 BUSY。
- TestApp 普通聊天与 Eval Bridge 同 UID，AIAgent 无法进一步区分，因此 TestApp 计划必须在租约期间禁用普通聊天入口。
- 默认 TTL 为 120 秒，允许范围 60 秒到 10 分钟；低于 Agent 30 秒 deadline 加桥接收尾余量的 TTL 不接受。异常进程退出后由 TTL 兜底。

### 3.5 状态修订号

- `VehicleStateMachine` 持有单调递增 `stateRevision`。
- 每次成功 Tool 状态变更、reset 或 apply patch 后递增。
- 快照把该值作为共享协议的 `environmentRevision` 输出。
- 失败的参数校验和只读查询不得递增。

## 4. 执行边界

### 4.1 本计划包含

- Debug-only AIDL 和 Service。
- 调用方验证和请求大小限制。
- 状态快照、Patch、reset、revision 和线程安全。
- Eval 租约、请求准入和主动场景隔离。
- correlation/clientMessageId 根 Trace 补齐。
- 版本指纹。
- 单元测试、源码接线测试、Debug/Release Manifest 验证和设备人工验收。

### 4.2 本计划不包含

- 不实现 Dataset、Grader、Judge、Report 或 Phoenix Dataset。
- 不修改 Tool 选择、Safety 规则、confirmation 业务语义或 AgentLoop 终止策略。
- 不修改主 AIDL 方法签名或 SDK JAR。
- 不改变 IMAGE、VOICE、CONTROL 的业务实现；只在 Eval 租约冲突时统一拒绝外部并发请求。
- 不增加第二套 VehicleStateMachine。
- 不向 Prompt 注入 evalRunId、caseId、correlationId 或 leaseToken。
- 不在 Release 注册或导出 Eval Service。
- 不自动修改 TestApp 或 AIAgent_Eval 项目。

### 4.3 执行中必须停下来询问的情况

- Debug AIDL 无法仅通过 `src/debug` 编译或 Release 仍包含接口/Service。
- TestApp 包名或 debuggable 校验在目标模拟器上无法稳定识别。
- 状态 Patch 需要改变现有业务 setter 的合法值范围。
- Eval 租约需要改变主 AIDL ABI 才能工作。
- 为获取版本指纹必须把密钥、local.properties 或完整 Prompt 写入结果。
- 设备上主动场景无法在租约期间可靠暂停，仍会修改 VehicleStateMachine。

## 5. 目标文件结构

### 5.1 新增 main 文件

- `app/src/main/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateSnapshot.java`
  - 不可变结构化快照；包含 schemaVersion、environmentRevision、capturedAt 和 8 个 systems Map。

- `app/src/main/java/com/hirain/aiagent/VirtualStateMachine/VehicleStatePatch.java`
  - 结构化局部状态变更，保留字段是否出现的信息，禁止用 Java 默认值区分“未传”和“传 false/0”。

- `app/src/main/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateMutationResult.java`
  - success、errorCode、errorPath、errorDetail、snapshot。

- `app/src/main/java/com/hirain/aiagent/eval/EvalAdmissionDecision.java`
  - ALLOW / DENY_BUSY，以及稳定 reasonCode。

- `app/src/main/java/com/hirain/aiagent/eval/EvalRequestPermit.java`
  - owner 请求准入后的单次关闭凭据，确保所有成功、拒绝、异常、取消和超时路径只结束一次 in-flight 计数。

- `app/src/main/java/com/hirain/aiagent/eval/EvalEnvironmentCoordinator.java`
  - 租约、owner UID、token、TTL、状态操作和请求准入。

- `app/src/main/java/com/hirain/aiagent/eval/EvalEnvironmentRegistry.java`
  - AIAgentService 与 Debug Service 在同一进程内共享 coordinator；Release 默认为空。

- `app/src/main/java/com/hirain/aiagent/eval/EvalRuntimeFingerprint.java`
  - Service 初始化时保存实际 TEXT 模型、Trace 模式和状态 Schema 版本，不包含密钥。

### 5.2 新增 debug 文件

- `app/src/debug/aidl/com/hirain/aiagent/eval/IAIAgentEvalDebug.aidl`
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugService.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugBinder.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugCallerValidator.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugRequest.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugResponse.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugProtocolCodec.java`
- `app/src/debug/java/com/hirain/aiagent/eval/EvalVersionFingerprintProvider.java`

### 5.3 修改文件

- `app/src/main/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateMachine.java`
- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- `app/src/main/java/com/hirain/aiagent/trace/TraceAttributeKeys.java`

主 AIDL、AgentRequest、AgentResponse 和 Runtime/AgentLoop 类原则上不修改。

### 5.4 新增测试文件

- `app/src/test/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateSnapshotTest.java`
- `app/src/test/java/com/hirain/aiagent/VirtualStateMachine/VehicleStatePatchTest.java`
- `app/src/test/java/com/hirain/aiagent/VirtualStateMachine/VehicleStateRevisionTest.java`
- `app/src/test/java/com/hirain/aiagent/eval/EvalEnvironmentCoordinatorTest.java`
- `app/src/test/java/com/hirain/aiagent/eval/EvalDebugProtocolTest.java`
- `app/src/test/java/com/hirain/aiagent/eval/EvalCallerValidationTest.java`
- `app/src/test/java/com/hirain/aiagent/eval/AIAgentServiceEvalWiringTest.java`
- `app/src/test/java/com/hirain/aiagent/trace/EvalCorrelationTraceTest.java`

## 6. 状态协议字段

快照 systems 一级键固定为：

```text
ac, door, window, seat, speed, chassis, fragrance, dms
```

二级字段使用当前 POJO 的 camelCase 名称：

- AC：acStatus、acDriveTemp、acAssistTemp、acFanIntensity、acEcoMode、acAnionStatus、acCleanMode、acCycMode、acDriveSweepAuto、acAssistSweepAuto、acDriveLeftAirOutlet、acDriveRightAirOutlet、acAssistAirOutletMode、acAssistLeftAirOutlet、acAssistRightAirOutlet。
- Door：doorFlOpen、doorFrOpen、doorRlOpen、doorRrOpen、doorLocked。
- Window：windowFlOpen、windowFrOpen、windowRlOpen、windowRrOpen、windowTopOpen、sunShadowOpen、windowFDefrosting、windowRHeat、mirrorLHeat、mirrorRHeat、noWindowOpeningPassengers。
- Seat：seatFlHeat、seatFrHeat、seatRlHeat、seatRrHeat、seatFlAir、seatFrAir、seatRlAir、seatRrAir、seatMassageMode、seatMassageIntensity、steeringHeat。
- Speed：vehicleSpd。
- Chassis：chassisMode。
- Fragrance：fragType、fragIntensity。
- DMS：dmsDriveFatigue、dmsDriveDistractionLevel、dmsDriveEmotion。

Patch 必须复用现有合法范围：温度 16–31、风量 1–7、百分比 0–100、车速 0–240，以及现有字符串枚举。未知 system/field、错误 JSON 类型或越界值整体失败，不允许部分字段先写入。

`VehicleStateSnapshot` 的跨系统字段固定为 `schemaVersion`、`environmentRevision`、`capturedAt`、`systems`。`capturedAt` 按电脑端 Schema 输出带 UTC 时区的 ISO-8601 字符串；AIAgent 内部仍使用 `TimeProvider.nowMillis()` 取时，再统一格式化，不能把 epoch millis 填进同名外层字段。

## Phase 1：建立协议边界和 Debug-only Service 骨架

### Task 1.1：确认共享 Schema 与 Android 内部 Debug 协议

**Files**

- Read: `D:\code\android\AndroidStudioProjects\AIAgent_Eval\schemas\*.json`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugRequest.java`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugResponse.java`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugProtocolCodec.java`
- Test: `app/src/test/java/com/hirain/aiagent/eval/EvalDebugProtocolTest.java`

**工作步骤**

- [ ] 实施前确认电脑端 Phase 1 已生成 protocolVersion、command、result 和 vehicle-state Schema；若不存在，停止并先完成电脑端协议任务。
- [ ] 不复制电脑端外层 Schema 作为第二份权威定义；AIAgent Phase 1 只实现内部 Debug DTO，并先用本仓库最小合法/非法 Fixture 验证 codec。与 TestApp mapper 的跨仓库契约测试留到 Phase 4，不能让尚未实现的 TestApp 反向阻塞本 Phase。
- [ ] 定义内部 operation：ACQUIRE_ENVIRONMENT、RESET_STATE、APPLY_STATE、READ_STATE、RELEASE_ENVIRONMENT、GET_VERSION。
- [ ] protocolVersion 复用电脑端 `ProtocolVersion {major, minor, schemaHash}` 对象形状，不另行使用裸整数/字符串；major 不兼容拒绝，minor/schemaHash 用于诊断和契约检查。
- [ ] Request 字段：protocolVersion、operation、correlationId、leaseToken、ttlMs、statePatch。
- [ ] Response 字段：protocolVersion、success、status、errorCode、errorDetail、leaseToken、leaseExpiresAtEpochMs、snapshot、versionFingerprint。
- [ ] 限制 requestJson 最大 128 KiB、correlationId 长度和 token 长度；拒绝未知字段和未知 operation。
- [ ] 协议错误返回结构化 response，不向 Binder 调用方抛出 JSON 解析堆栈。
- [ ] `leaseToken` 永远不写 Logcat、Trace、Prompt 或 AgentResponse。
- [ ] `leaseToken/leaseExpiresAtEpochMs` 只供 TestApp 内部 manager 使用；TestApp 生成电脑端 operationResult 时必须剥离 token，外层只允许 leaseActive/状态摘要。
- [ ] GET_VERSION 不需要租约；其余状态操作按 coordinator 规则校验。

**测试重点**

- 正确解析最小 ACQUIRE 和 APPLY。
- 缺失 protocolVersion、未知 operation、过大 payload、错误字段类型返回稳定 errorCode。
- Response 序列化不包含 null 之外的虚构默认值。

### Task 1.2：创建独立 Debug AIDL 和 Service

**Files**

- Create: `app/src/debug/aidl/com/hirain/aiagent/eval/IAIAgentEvalDebug.aidl`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugService.java`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugBinder.java`
- Create: `app/src/debug/java/com/hirain/aiagent/eval/EvalDebugCallerValidator.java`
- Create: `app/src/debug/AndroidManifest.xml`
- Test: `app/src/test/java/com/hirain/aiagent/eval/EvalCallerValidationTest.java`

**工作步骤**

- [ ] AIDL 只声明同步 `String execute(String requestJson)`，不加入主业务接口。
- [ ] Debug Manifest 注册显式 Service：`com.hirain.aiagent.eval.EvalDebugService`，exported=true，不添加隐式 intent-filter，也不设置独立 `android:process`，确保它与 AIAgentService 共享同一个 Registry/VehicleStateMachine。
- [ ] Binder 每次执行先读取 callingUid，再做包名、debuggable 和 payload 校验。
- [ ] 固定允许包名 `com.hirain.aiagent.test`；UID 对应多个包时必须包含该包且该包自身 debuggable。
- [ ] 校验失败返回 CALLER_NOT_ALLOWED，不输出调用方安装路径、签名或其他敏感信息。
- [ ] Service 从 `EvalEnvironmentRegistry` 读取 coordinator；主 Service 未初始化时返回 AGENT_NOT_READY。
- [ ] Binder 工作不在主线程执行耗时文件扫描；版本 Prompt hash 使用缓存。
- [ ] 不在 `src/main/AndroidManifest.xml` 注册任何 Eval 组件。

**构建验证**

```powershell
.\gradlew.bat processDebugMainManifest
.\gradlew.bat compileDebugAidl
.\gradlew.bat processReleaseMainManifest
```

Expected：Debug 生成 AIDL/Service；Release merged manifest 不包含 `EvalDebugService`。

### Phase 1 完成门

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.eval.*"
.\gradlew.bat processDebugMainManifest processReleaseMainManifest
```

完成标准：Debug 协议和 Binder 骨架可编译，Release 不暴露入口；状态操作此时可以返回 AGENT_NOT_READY/NOT_IMPLEMENTED，不得伪造成功。

## Phase 2：结构化车辆状态、原子 Patch 和环境租约

### Task 2.1：实现不可变快照和 stateRevision

**Files**

- Create: `VehicleStateSnapshot.java`
- Modify: `VehicleStateMachine.java`
- Test: `VehicleStateSnapshotTest.java`
- Test: `VehicleStateRevisionTest.java`

**工作步骤**

- [ ] 将 8 个内部状态对象的访问统一置于 VehicleStateMachine 实例锁保护下；现有 setter/getter 改为同步访问。
- [ ] 增加 long `stateRevision`，初始为 0。
- [ ] 每个成功业务 setter 在完成状态写入后递增一次；校验失败不得递增。
- [ ] `snapshot()` 在同一锁内复制所有字段，禁止把内部可变 POJO 或可写 Map 暴露出去。
- [ ] Snapshot 使用第 6 节英文字段，不复用当前面向 Prompt 的中文 JSON key。
- [ ] capturedAt 由可注入 TimeProvider 或方法参数提供，并统一格式化为 UTC ISO-8601；单测固定 nowMillis 后断言精确字符串，不依赖真实时间。
- [ ] Snapshot 的 environmentRevision 等于读取时 stateRevision。
- [ ] 现有 `getAcStatus()` 等中文业务输出保持不变，避免影响 Context 和 Tool 回答。

**回归测试**

- 现有合法/非法 setter 返回文本不变。
- 业务 setter 成功 revision +1，失败不变，只读不变。
- Snapshot Map 不能被调用方修改。
- 并发 snapshot 不出现一半旧值、一半新值。

### Task 2.2：实现 reset 和全量校验后原子 Patch

**Files**

- Create: `VehicleStatePatch.java`
- Create: `VehicleStateMutationResult.java`
- Modify: `VehicleStateMachine.java`
- Test: `VehicleStatePatchTest.java`

**工作步骤**

- [ ] VehicleStatePatch 保留原始字段存在性，以支持 false、0 和 null 的明确语义。
- [ ] 在任何写入前完成 system、field、类型、范围和枚举的全量校验。
- [ ] 未知字段返回 UNKNOWN_STATE_PATH；类型错误返回 TYPE_MISMATCH；越界返回 VALUE_OUT_OF_RANGE；枚举错误返回 INVALID_ENUM。
- [ ] 验证通过后在单个 synchronized 临界区直接应用全部字段，并只递增一次 revision。
- [ ] Patch 为空时返回 NO_CHANGES，不递增 revision。
- [ ] reset 先在锁外创建 8 个默认 State，再在同一个 VehicleStateMachine 锁内一次性替换内部引用并递增一次 revision。为此可将当前 8 个 `private final` 字段改为仅由实例锁保护的非 final 引用；这些引用仍不得对外暴露，不能逐字段调用业务 setter 导致 revision 连续增加或产生半重置状态。
- [ ] reset/apply 返回操作后的完整 Snapshot，避免 TestApp 再猜测写入是否成功。
- [ ] 任一字段失败时，所有状态和 revision 保持操作前值。
- [ ] 不通过调用返回中文字符串判断 setter 成败。

**测试矩阵**

- 每个系统至少一条合法 Patch。
- false、0、边界值、非法枚举、未知字段、错误类型。
- 混合 Patch 中最后一项非法时前面字段不能落地。
- reset 后与新 VehicleStateMachine 默认 Snapshot 一致。

### Task 2.3：实现 EvalEnvironmentCoordinator

**Files**

- Create: `EvalAdmissionDecision.java`
- Create: `EvalRequestPermit.java`
- Create: `EvalEnvironmentCoordinator.java`
- Create: `EvalEnvironmentRegistry.java`
- Create: `EvalRuntimeFingerprint.java`
- Test: `EvalEnvironmentCoordinatorTest.java`

**工作步骤**

- [ ] Coordinator 构造时注入 VehicleStateMachine、TimeProvider 和 isEnvironmentIdle supplier；用于 acquire/reset/apply 的 idle 必须保守定义为 `ActiveRequestRegistry.current() == null`、没有主动/被动场景执行且 coordinator `in-flight == 0`。`current()` 会一直保留到 worker 完全退出，因此不能因 ActiveRequest 已抢占终态就提前判空闲；终态 callback 早于 registry release 正是 TestApp 需要短轮询 READ 的原因。
- [ ] acquire(uid, correlationId, ttl) 只在 idle 且无有效租约时成功，使用 `SecureRandom` 生成至少 128 bit、URL-safe 的内部 token；不得使用可预测计数或 correlationId 派生 token。
- [ ] TTL 默认 120 秒，并限制在 60 秒到 10 分钟；越界请求拒绝而非静默放宽。
- [ ] reset/apply/read/release 校验 callingUid、token 和租约有效期。
- [ ] 每次成功的租约状态操作和 owner Agent 请求开始时刷新 expiry；所有成功的 lease-authenticated Debug response 都返回新的 leaseExpiresAtEpochMs，TestApp 不自行猜测到期时间。GET_VERSION 不持有租约时该字段为空。
- [ ] reset/apply 只在 environment idle 时执行；Agent 正在运行时返回 AGENT_BUSY。
- [ ] read 在 Agent 终态后读取；运行中读取默认返回 AGENT_BUSY，避免把中间状态当终态。
- [ ] owner 请求准入使用 `beginRequest(callingUid)`，成功时返回一次性 EvalRequestPermit 并增加 in-flight 计数；所有退出路径调用 permit.close()，重复 close 不得重复递减。
- [ ] release/reset/apply 在 in-flight > 0 时拒绝；read 只在 in-flight == 0 时返回终态快照。
- [ ] 租约在 in-flight > 0 时即使到达 expiry 也不能立即开放给其他 UID；先标记 expired/pending release，等最后一个 permit 关闭后清除。这样不会在正在执行的 Tool 中途解除隔离。
- [ ] release 在有活跃 owner 请求时拒绝；终态后释放并清除 owner/token/correlation/expiry。
- [ ] 无 in-flight 的租约过期时清理 token；下一次 acquire 可继续。
- [ ] `checkAdmission(callingUid)`：无租约 ALLOW；owner UID ALLOW 并由 beginRequest 形成 permit；其他 UID DENY_BUSY。
- [ ] `isLeaseActive()` 供主动场景入口快速跳过执行。
- [ ] Registry 使用 AtomicReference 安装/卸载 coordinator；重复安装不同实例要失败并记录错误。
- [ ] Registry 不提供状态写方法，只暴露当前 coordinator Optional。
- [ ] Coordinator 提供仅供 AIAgentService 生命周期调用的 `shutdown()`：先拒绝新请求/状态操作，再使 token 失效；若仍有 in-flight，只等待 permit 自然 close 做内部计数收敛，不把正常 `release()` 的“活跃请求时拒绝”规则错误用于 Service 销毁。

**测试矩阵**

- acquire 成功、已有租约、Agent busy、TTL 越界。
- owner 与非 owner admission。
- token 错误、UID 错误、过期、重复 release。
- owner 请求开始/终态 permit、重复 close、异常 close、过期发生于 in-flight 中、最后一个 permit 关闭后清理。
- Service shutdown 发生于空闲/有 in-flight 两种情况，均不再允许新操作且不出现负计数。
- reset/apply revision、失败不污染。
- 测试时间推进后 TTL 自动失效。

### Phase 2 完成门

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.VirtualStateMachine.*"
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.eval.EvalEnvironmentCoordinatorTest"
```

完成标准：状态操作原子、revision 可验证、租约可以阻止非 owner；尚未接 Service 的部分不得声称设备闭环完成。

## Phase 3：接入 AIAgentService、Trace 和版本指纹

### Task 3.1：安装 coordinator 并接入请求准入

**Files**

- Modify: `AIAgentService.kt`
- Test: `AIAgentServiceEvalWiringTest.java`

**工作步骤**

- [ ] 在 VehicleStateMachine、ActiveRequestRegistry 和 TEXT AgentConfig 就绪后，仅当 BuildConfig.DEBUG 时创建并安装 coordinator。
- [ ] isEnvironmentIdle 同时要求 `ActiveRequestRegistry.current() == null`、主动/被动场景均未执行和 Eval in-flight 为 0；不能只看 ActiveRequest 的终态枚举，也不能只看 TEXT 槽位。
- [ ] onDestroy 先从 Registry compare-and-uninstall 当前 coordinator，再调用 coordinator.shutdown() 使 Debug Binder 后续返回 AGENT_NOT_READY；不能调用可能因 in-flight 而拒绝的普通 release 冒充生命周期清理。随后按既有顺序关闭其他运行时资源，迟到 permit.close 只能做幂等内部收敛。
- [ ] AIAgentBinder.processAgentRequest 在 Binder 线程入口捕获 callingUid，并在进入各 inputType handler 前执行 Eval admission。
- [ ] 当租约存在且 owner 发起 TEXT 时，beginRequest 返回的 EvalRequestPermit 必须随 requestId 进入 handleTextRequest；ActiveRequest BUSY/DUPLICATE、worker post 失败、setup exception、正常完成、取消、超时和 Service 销毁路径都必须最终 close。
- [ ] permit 表示实际执行是否已经静止，不能仅因 AgentResponse 已 dispatch 就在 timeout/cancel 路径提前释放；正常 worker 路径必须先完成 HTTP/Trace/Session 清理并执行 `ActiveRequestRegistry.release()`，最后才 close permit。未进入 worker 的失败路径完成自身清理后 close；TestApp 在 callback 后通过 READ 的短轮询等待 permit 归零。
- [ ] 发生租约冲突时生成/保留 requestId，返回结构化 BUSY AgentResponse；不得静默丢请求。
- [ ] 冲突响应保留原 clientMessageId，使调用方能够关联结果。
- [ ] owner UID 请求继续走现有 handleTextRequest、ActiveRequestRegistry、deadline、Runtime 和 response dispatcher。
- [ ] 主动场景在 Eval 租约有效时跳过 LLM/Tool 执行，只保留必要的摄像头资源维护；不得修改车辆状态。
- [ ] 不把 leaseToken 或 Eval Case 元数据放入 AgentRequest.extraContext。
- [ ] 对同 UID 的普通 TestApp 请求无法在 AIAgent 内区分，源码注释明确由 TestApp UI gate 保证。
- [ ] Eval 只评估 TEXT；租约期间 owner UID 的 IMAGE/VOICE/CONTROL 也由 AIAgent 准入层结构化拒绝，TestApp gate 同时禁止这些人工入口。不能只依赖 UI gate，否则同 UID 的非 TEXT 请求可能绕过 in-flight 隔离；本期不为这些类型建立新的 Eval permit 语义。

**回归边界**

- 无租约时所有现有请求行为不变。
- Release Registry 永远为空，准入检查为 no-op。
- Eval BUSY 不占用 ActiveRequestRegistry 槽位。
- permit/in-flight 在所有异常和终态路径最终回到 0；TestApp callback 到达但 worker 尚未退出时 READ 明确返回 AGENT_BUSY，而不是中间快照。

### Task 3.2：补齐根 Trace correlation 和环境证据

**Files**

- Modify: `TraceAttributeKeys.java`
- Modify: `AIAgentService.kt`
- Test: `EvalCorrelationTraceTest.java`
- Update: `AIAgentServiceTraceWiringTest.java`（如现有断言需要扩展）

**新增属性**

- 复用：`client_message.id`。
- 新增：`eval.environment.active`。
- 新增：`eval.environment.revision`。

**工作步骤**

- [ ] 抽取统一 request identity 记录 helper，在根 Trace 创建后立即写 requestId、clientMessageId 和环境字段。
- [ ] accepted、ActiveRequest BUSY/DUPLICATE、Eval lease BUSY 三类根 Trace 都调用该 helper。
- [ ] Runtime 后续重复写相同 clientMessageId 允许，但值不一致必须测试失败。
- [ ] 不新增独立 eval.correlationId；`client_message.id` 就是跨系统 correlation 的承载字段。
- [ ] 环境 revision 取请求准入时 Snapshot，表示该请求开始时的车辆状态版本。
- [ ] `gen_ai.output` 保持单次模型输出语义，不增加“最终响应”别名。
- [ ] AgentResponse 的完整文本仍由 TestApp 结果存储传输；Trace 只保留现有 response success/error/length。
- [ ] 二次确认继续使用现有 confirmationId/status，不新建平行确认 ID。

**测试重点**

- 正常请求根 Trace 可按 correlation 查询。
- 准入拒绝请求也有 client_message.id。
- 多轮 Tool Trace 不重复生成第二个根。
- 无租约时 environment.active=false；租约请求为 true 且 revision 正确。

### Task 3.3：完成 Debug operation 分发和版本指纹

**Files**

- Modify: `EvalDebugBinder.java`
- Create: `EvalVersionFingerprintProvider.java`
- Modify: `AIAgentService.kt`
- Test: `EvalDebugProtocolTest.java`

**工作步骤**

- [ ] Binder 将六种 operation 映射到 coordinator，不允许反射式任意方法调用。
- [ ] ACQUIRE 返回 leaseToken、leaseExpiresAtEpochMs 和当前 Snapshot；RESET/APPLY/READ 返回 Snapshot 及刷新后的 leaseExpiresAtEpochMs；RELEASE 返回结构化 operation status。token/expiry 仅存在内部 response，GET_VERSION 不虚构租约字段。
- [ ] 所有响应包含当前 protocolVersion；major 不匹配直接失败。
- [ ] Service 初始化 TEXT AgentConfig 时保存实际使用的 modelName，而不是在 Debug Provider 再硬编码 qwen 名称。
- [ ] 版本指纹包含 packageName、versionName、versionCode、protocolVersion、stateSchemaVersion、textModel、traceContentMode。
- [ ] Prompt hash 对 `assets/prompts/` 的规范化相对路径排序，将每个文件的相对路径长度/路径字节、内容长度/内容字节依次送入 SHA-256；不能只拼接正文导致不同文件划分得到同一 hash。首次计算后进程内缓存。
- [ ] Prompt hash 不返回 Prompt 正文；模型/API 配置不返回 Key、base auth header 或 local.properties。
- [ ] 源码 revision 无可靠注入时返回 null；不得伪造 Git SHA。APK version 作为本期 build 标识。
- [ ] operation 未实现、coordinator 未就绪和状态验证失败均返回稳定 errorCode。

### Phase 3 完成门

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.eval.*"
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.trace.EvalCorrelationTraceTest"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

完成标准：Debug Service 能真实操作 AIAgentService 使用的唯一状态机；普通无租约路径和现有测试保持通过。

## Phase 4：三方契约、设备验收和 Release 边界

### Task 4.1：与 TestApp 执行协议契约测试

**Files**

- Add Fixture: `app/src/test/resources/eval/*.json`
- Modify: `EvalDebugProtocolTest.java`
- Coordinate with: TestApp `tests/fixtures`/Debug test output

**工作步骤**

- [ ] 使用 AIAgent 与 TestApp 共同维护的内部 Debug request/response Fixture 验证 ACQUIRE/APPLY/READ/RELEASE Codec；这些 Fixture 不冒充电脑端外层 EvalCommand/EvalResultEnvelope。
- [ ] 使用 AIAgent Debug Service 实际输出生成脱敏内部 response/snapshot Fixture；只把其中 VehicleStateSnapshot 直接交给电脑端 vehicle-state Schema 校验。
- [ ] 由 TestApp mapper 剥离 leaseToken/leaseExpiresAtEpochMs 并生成外层 operationResult 后，再由电脑端 eval-result Schema 校验；电脑端不得直接消费 AIAgent 内部 Debug response。
- [ ] 检查 systems 一级键、字段类型、revision 和 timestamp。
- [ ] 检查 errorCode 在三方文档中的含义一致。
- [ ] 协议 Fixture 只保留模拟状态，不含真实用户输入或 Prompt。
- [ ] schema major 变化必须先更新电脑端和两 Android 计划，不允许单项目先行。

### Task 4.2：模拟器设备验收

**前置条件**

- Debug AIAgent 和 Debug TestApp 已安装。
- TestApp EvalDebugClient 已能绑定 Debug Service。
- Phoenix 已运行；当前 PowerShell 环境执行 `& 'D:\code\android\forSdk\Sdk\platform-tools\adb.exe' reverse tcp:6006 tcp:6006`，若 SDK 路径变化则从 `local.properties` 重新解析。

**验收顺序**

- [ ] GET_VERSION：确认 APK、Prompt hash、model、Trace mode 和 schema version 非伪造。
- [ ] ACQUIRE：空闲成功；同一 TestApp 重复 acquire 返回 LEASE_ALREADY_HELD；未授权第二 UID 调用 Debug Service 返回 CALLER_NOT_ALLOWED。Coordinator 的非 owner DENY_BUSY 由 JVM 测试覆盖。
- [ ] RESET + READ：得到默认快照和递增 revision。
- [ ] APPLY：设置 speed、door、ac，读取值与 revision。
- [ ] 非 owner 普通请求：若现场存在 Launcher/第二测试客户端，则验证收到 BUSY AgentResponse 且状态不变；没有第二 caller 时保留为 JVM/源码接线验收，不能用同 UID TestApp 冒充非 owner。
- [ ] owner TEXT 低风险 Tool：AgentResponse、状态和 Trace 一致。
- [ ] Safety deny：无 dispatch、状态不变。
- [ ] 二次确认：两请求独立 correlation、共享 confirmationId、状态最终变化。
- [ ] RELEASE：终态后成功；释放后普通请求恢复。
- [ ] TTL：TestApp 强制结束后等待过期，可重新 acquire。
- [ ] 主动场景：租约期间不触发 Tool 状态变更。

每条测试只持久化 TestApp 生成的外层 command/result JSON，并记录 AgentResponse、before/after revision 和 Phoenix traceId；AIAgent 内部 Debug response 必须经 mapper 剥离 leaseToken/leaseExpiresAtEpochMs 后才能进入记录，Logcat 只作为诊断附件。

### Task 4.3：Release 缺失验证和文档更新

**Files**

- Update: `docs/overview/trace-module-overview.md`（只补充已实现的 Eval 关联属性）
- Update: `README.md`（只补充 Debug Eval 能力和 Release 边界）
- Update: `docs/plan_overall/eval/05-aiagent-eval-adaptation-implementation-plan.md` 完成状态

**验证命令**

```powershell
.\gradlew.bat processReleaseMainManifest
.\gradlew.bat assembleRelease
.\gradlew.bat testDebugUnitTest
```

**检查项**

- [ ] Release merged manifest 不含 EvalDebugService。
- [ ] Release APK 不存在可绑定 Eval component。
- [ ] 主 IAIAgentAidlInterface 无新增 Eval 方法。
- [ ] Release 无状态写入口，Registry 未安装。
- [ ] README 不声称 Eval 系统集成在 AIAgent APK 内。
- [ ] 文档明确 FULL_DEBUG Trace 的隐私限制。

### Phase 4 完成门

完成标准同时满足：

- 全量 JVM 测试通过。
- Debug/Release 构建通过。
- 至少一个真实低风险 TEXT Eval 闭环通过。
- Safety deny 和二次确认至少人工验证一条。
- Release 无 Eval Debug 入口。
- 若设备或 TestApp 未就绪，必须标记“代码完成，三方设备验收未关闭”。

## 7. Debug operation 状态码

建议固定以下 error/status，避免用自然语言做程序判断：

```text
OK
AGENT_NOT_READY
CALLER_NOT_ALLOWED
PROTOCOL_VERSION_UNSUPPORTED
PAYLOAD_TOO_LARGE
INVALID_REQUEST
UNKNOWN_OPERATION
AGENT_BUSY
LEASE_ALREADY_HELD
LEASE_NOT_FOUND
LEASE_EXPIRED
LEASE_OWNER_MISMATCH
LEASE_TOKEN_INVALID
STATE_VALIDATION_FAILED
STATE_OPERATION_FAILED
VERSION_FINGERPRINT_FAILED
INTERNAL_ERROR
```

errorDetail 用于人读；TestApp mapper 只依赖这里的稳定内部 errorCode，再映射成电脑端外层 operationResult/bridgeError。电脑端不得直接依赖 AIAgent 内部 response。

## 8. 测试分层

### 8.1 JVM 单元测试

- 状态快照、Patch、revision、租约、协议和 Trace 属性。
- 使用 fake time 和 fake idle supplier。
- 不启动 Android Service，不依赖设备或 Phoenix。

### 8.2 源码/构建接线测试

- 验证 AIAgentService 安装/卸载 coordinator。
- 验证根 Trace identity helper 覆盖 accepted/rejected。
- 验证主 AIDL 未变。
- 验证 Debug/Release Manifest 差异。

### 8.3 设备契约测试

- 真实 Binder callingUid。
- Debug Service 包校验。
- 唯一 VehicleStateMachine。
- TestApp 结构化结果。
- Phoenix correlation 查询。

## 9. 最终验收清单

### 功能

- [ ] Debug TestApp 可以 acquire/reset/apply/read/release。
- [ ] 8 个 systems 快照字段符合 Schema。
- [ ] Patch 全量校验且无部分写入。
- [ ] Tool/reset/apply revision 递增正确。
- [ ] owner/非 owner 准入正确。
- [ ] TTL 和异常恢复正确。
- [ ] ActiveRequest 槽位释放后才关闭 permit，callback 早到时 READ 返回 AGENT_BUSY 而非中间快照。
- [ ] 租约期间 owner 的非 TEXT 请求也被结构化拒绝。
- [ ] correlation 在根 Trace 可查询。
- [ ] confirmation 证据保持现有语义。
- [ ] 版本指纹不含凭证。

### 边界

- [ ] 主 AIDL 和 SDK 不变。
- [ ] Eval 元数据不进 Prompt。
- [ ] leaseToken/leaseExpiresAtEpochMs 不进外层结果、日志或 Trace。
- [ ] AIAgent 不评分、不生成报告。
- [ ] 不创建第二状态机。
- [ ] Logcat 不作为结果通道。
- [ ] Release 不暴露 Debug Service。

### 验证

- [ ] `testDebugUnitTest` 通过。
- [ ] `assembleDebug` 通过。
- [ ] `assembleRelease` 通过。
- [ ] 三方合法/非法 Fixture 通过。
- [ ] 真实设备低风险闭环通过。

## 10. 实施顺序总结

1. **Phase 1：** 先建立不影响主 AIDL 的 Debug 协议和构建隔离。
2. **Phase 2：** 再让唯一 VehicleStateMachine 具备可靠快照、Patch、revision 和租约。
3. **Phase 3：** 接入 Service 准入、Trace 和版本指纹，形成真实可调用能力。
4. **Phase 4：** 最后与 TestApp/电脑端做契约和设备验收，并证明 Release 无入口。

该计划的完成标志不是“新增了 Debug Service”，而是电脑端 Case 能从确定状态开始，TestApp 收到真实 AgentResponse，Phoenix 能按同一 correlation 找到唯一 Trace，且 Release 不携带状态控制入口。
