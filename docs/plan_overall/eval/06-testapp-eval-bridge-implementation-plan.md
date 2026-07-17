# AIAgentTestApp Eval 桥接改进详细实现计划

> 文档性质：详细实施计划，不代表代码已经实现。
> 目标项目：`D:\code\android\AndroidStudioProjects\AIAgentTestApp`
> 协同项目：AIAgent、AIAgent_Eval
> 前置文档：`02-testapp-eval-bridge-design-outline.md`、`04-aiagent-eval-pc-implementation-plan.md`、`05-aiagent-eval-adaptation-implementation-plan.md`
> 计划执行方式：后续由子 Agent 按 Phase 使用 Goal 模式逐步实现，每个 Phase 完成后独立验收。

---

## 0. Goal 窗口交接与当前工程快照

本计划的权威文件位于兄弟项目：

```text
D:\code\android\AndroidStudioProjects\AIAgent\docs\plan_overall\eval\06-testapp-eval-bridge-implementation-plan.md
```

新 Goal 窗口的工作目录是 `D:\code\android\AndroidStudioProjects\AIAgentTestApp`。若窗口不能读取兄弟仓库，用户需要先把本计划复制到 TestApp 的 `docs/`；不得让执行者根据聊天摘要重建计划。首次只创建 Phase 1 Goal，完成并通过 Phase 1 出口后再创建下一 Phase。

跨项目硬依赖：TestApp Phase 1 开始前，电脑端计划 Phase 1 的 protocolVersion 与三份 Schema 必须已落盘；若电脑端尚未提交 canonical command/result fixture，TestApp 先维护最小样例并用电脑端 Schema 校验，不把 fixture 缺失误判为协议缺失。TestApp Phase 2 开始前，AIAgent 至少完成其 Phase 3，并能提供真实 `IAIAgentEvalDebug.aidl`、Debug Service 和 operation；TestApp Phase 3 依赖 AIAgent 正式主链路仍通过回归。TestApp Phase 4 的 Task 4.1–4.5 可先独立实现，Task 4.6 三方验收再等待电脑端 Phase 4 自动执行能力，避免形成双方互相等待。依赖未满足时标记“等待前置 Phase”，不得手写临时 AIDL、伪造 response 或扩展第二通道。

2026-07-16 最终审计时确认：TestApp 工作树干净；applicationId/namespace 为 `com.hirain.aiagent.test`，compileSdk 36、minSdk 24、targetSdk 34、Java 11；当前未启用 AIDL、没有 JSON/JUnit 依赖，正式 Facade 已在 `MyApplication` 初始化，并通过 `CopyOnWriteArrayList` 支持多个本地 listener。执行前必须重新检查这些事实和 `git status --short`，如有用户新增修改则保留并在重叠处停下来确认。当前 `adb` 未加入 PowerShell PATH，`local.properties` 的 `sdk.dir` 为 `D:/code/android/forSdk/Sdk`，设备命令应解析该配置或直接使用 `D:\code\android\forSdk\Sdk\platform-tools\adb.exe`。

---

## 1. 计划目标

本计划负责把现有 AIAgentTestApp 改造成电脑端 AIAgent_Eval 与车机端 AIAgent 之间的 Debug 评估桥接器，同时继续保留 TestApp 原有的人工聊天、会话管理和语音调试能力。

改造完成后，TestApp 应支持以下闭环：

1. 接收电脑端生成的结构化 EvalCommand。
2. 通过 AIAgent 新增的 Debug Eval AIDL 获取环境租约并控制虚拟车辆状态。
3. 通过现有正式 AIAgent SDK/AIDL 发送真实 AgentRequest，而不是绕过正式运行链路。
4. 采集 AgentResponse、操作结果、请求前后车辆状态和版本指纹。
5. 将结构化 EvalResultEnvelope 原子写入 Debug 专用结果目录。
6. 允许电脑端优先使用 `adb shell run-as` 读取结果；Logcat 仅承担诊断职责。
7. 第一阶段先形成可靠的半自动闭环，后续在同一入口上增加 ADB 自动触发，不额外引入第二套桥接架构。

本计划只负责 TestApp 的桥接与采集职责，不负责：

- 在 TestApp 中实现评分、LLM Judge、报告生成或数据集管理。
- 修改 AIAgent 正式业务 AIDL 的方法签名。
- 直接访问 Phoenix 或解析 Trace。
- 让电脑端直接指定 AIAgent 的真实 `requestId`。
- 使用 Logcat 作为正式 Eval 数据通道。
- 建设通用远程控制框架、后台常驻评估服务或跨设备任务调度系统。

---

## 2. 现状与改造原则

### 2.1 当前运行链路

现有 TestApp 通过 `AIAgent.java` Facade 绑定 AIAgent 的正式 AIDL Service。`MainActivity` 负责：

- 构造 AgentRequest；
- 生成 `requestId` 和 `clientMessageId`；
- 接收 AgentResponse；
- 维护取消、超时、会话、语音和界面状态。

Eval 改造必须复用这条正式请求链路，以确保评估覆盖真实 Runtime、Context、AgentLoop、Tool、安全审核、Memory 和 Trace，而不是只测试一个旁路接口。

### 2.2 核心设计约束

1. **Debug 隔离**：Eval 桥接代码、Eval AIDL 客户端、自动触发入口和相关资源放在 `src/debug`；Release 包中不得出现 Eval 入口。
2. **正式请求复用**：文本评估继续通过现有 AIAgent Facade 发起。
3. **ID 所有权收敛**：
   - 电脑端生成 `correlationId`；
   - TestApp 将其作为 `clientMessageId`；
   - TestApp 在发送正式请求前生成真实 `requestId`；
   - 电脑端不指定真实 `requestId`；
   - AIAgent Response 和 Trace 返回/记录真实 `requestId`。
4. **结构化结果唯一化**：每条命令写一份以 `correlationId` 命名的结果文件。
5. **单向状态机**：正常执行为 PENDING → RUNNING → TERMINAL；已获得安全 correlationId 但完整校验失败时允许 PENDING → TERMINAL。任何状态都不能回退，TERMINAL 不可覆盖。
6. **环境租约隔离**：所有车辆状态读写和 Eval 请求都必须绑定 AIAgent 返回的环境 token；token 只存在 TestApp 进程内存中。
7. **先闭环、后自动化**：Phase 2 先完成可人工触发的环境控制子闭环，Phase 3 加入真实 AgentResponse 后才形成最小评估闭环，Phase 4 才开放 ADB 自动执行参数。

---

## 3. 三方协议边界

### 3.1 唯一协议来源

电脑端 AIAgent_Eval 项目中的 Schema 是跨项目协议标准。Android 两个 App 是协议实现方，不各自发展独立协议。

实施时需要遵守：

- 协议字段名称、枚举值、必填性和版本号与电脑端 Schema 一致。
- TestApp 可以创建 Android 内部 DTO，但它们必须是一一映射的传输实现。
- AIAgent 的 Debug AIDL 可以使用 JSON 字符串降低跨 App Parcelable 维护成本；其内部 DTO 只需与共享协议的版本、operation 和 VehicleStateSnapshot 对齐，不得冒充或直接套用电脑端 EvalCommand/EvalResultEnvelope Schema。
- 协议不兼容时明确返回 `PROTOCOL_VERSION_UNSUPPORTED`，不得静默忽略字段。

这里需要区分两层传输：

- 外层“电脑端 ↔ TestApp”使用共享 EvalCommand/EvalResultEnvelope/VehicleStateSnapshot Schema，TestApp 必须完整实现。
- 内层“TestApp ↔ AIAgent Debug Service”使用 `EvalDebugRequest/EvalDebugResponse`，只承载环境控制，并允许包含仅存在内存的 leaseToken/leaseExpiresAtEpochMs。
- `EvalDebugProtocolMapper` 是两层之间的唯一转换点；它必须剥离 token，把内部状态/错误映射成外层 operationResult。AIAgent 内部 response 不能直接写入电脑端结果文件。

### 3.2 EvalAction 最小集合

Demo 阶段只实现以下动作：

| 动作 | TestApp 职责 | 主要调用目标 |
|---|---|---|
| `ACQUIRE_ENVIRONMENT` | 获取独占环境 token 并保存于内存 | AIAgent Debug Eval Service |
| `RESET_STATE` | 将虚拟车辆状态恢复基线 | AIAgent Debug Eval Service |
| `APPLY_STATE` | 原子应用车辆状态 Patch | AIAgent Debug Eval Service |
| `READ_STATE` | 读取结构化车辆状态快照 | AIAgent Debug Eval Service |
| `SEND_TEXT` | 发起真实 TEXT 请求并等待 AgentResponse | AIAgent 正式 Service |
| `CANCEL_REQUEST` | 通过目标 correlationId 找到真实 requestId 并取消 | AIAgent 正式 Service |
| `CREATE_SESSION` | 创建评估会话 | AIAgent 正式 Service |
| `SWITCH_SESSION` | 切换评估会话 | AIAgent 正式 Service |
| `DELETE_SESSION` | 删除评估会话 | AIAgent 正式 Service |
| `GET_VERSION` | 读取可复现版本指纹 | AIAgent Debug Eval Service |
| `RELEASE_ENVIRONMENT` | 释放环境 token 并恢复人工操作 | AIAgent Debug Eval Service |

不在 Demo 中加入图片上传、Phoenix 查询、批量命令队列、脚本 DSL 或多设备分布式执行。

### 3.3 EvalCommand 关键字段

TestApp 至少解析并校验：

```text
protocolVersion
correlationId
action
payload
createdAt
timeoutMs（可选）
metadata（可选）
```

动作 payload 按动作分别校验。`SEND_TEXT` 至少包含：

```text
text
userId
sessionId
personaId
extraContext（可选）
```

`CANCEL_REQUEST` 使用 `targetCorrelationId`，不接收电脑端提供的真实 `requestId`。

### 3.4 EvalResultEnvelope 关键字段

```text
protocolVersion
correlationId
bridgeState
action
requestId（可选，由 TestApp/AIAgent 运行链路产生）
agentResponse（可选）
operationResult（可选）
beforeVehicleState（可选）
afterVehicleState（可选）
versionFingerprint（可选）
timestamps
bridgeError（可选）
metadata（可选）
```

外层 envelope 的 createdAt、VehicleStateSnapshot.capturedAt 和 EvalTimestamps 统一使用带 UTC 时区的 ISO-8601 字符串；不得在这些同名字段中混用 epoch millis。嵌套 `agentResponse.timestamp` 按电脑端 Schema 保留 AIAgent Parcelable 的原始 epoch millis，不得擅自改写 AgentResponse 事实；Android 内部 lease expiry 可以使用 `leaseExpiresAtEpochMs`，但不进入外层结果。

终态结果必须满足以下三种条件之一：

- 有真实 `agentResponse`；
- 有结构化 `operationResult`；
- 有结构化 `bridgeError`。

不得生成既没有业务结果也没有桥接错误的“成功终态”。

---

## 4. Phase 1：Debug 工程隔离、协议模型与结果存储

### 4.1 阶段目标

先建立不依赖 AIAgent 连接的本地基础层：Debug source set、协议编解码、结果状态机、原子文件存储和可读性验证。此阶段不发送真实 Agent 请求。

### 4.2 Task 1.1：启用 Debug AIDL 与测试基础

#### 修改文件

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`（仅当当前依赖目录没有所需版本声明时）

#### 实现要求

1. 开启 Android AIDL 构建能力，使 `src/debug/aidl` 可生成 Debug 专用接口。
2. 当前项目没有 JSON 库；在 Version Catalog 增加 Gson 2.8.9，并添加 `debugImplementation(libs.gson)`，与 AIAgent 当前 Gson 版本保持一致，不引入第二套序列化框架或让 Eval JSON 依赖进入 Release。
3. 在 Version Catalog 增加 JUnit 4.13.2，并添加 `testImplementation(libs.junit)`。
4. 在 `android.buildFeatures` 开启 `aidl = true`，不改变其他 build feature。
5. 不修改 compileSdk、AGP、Kotlin、Java 或其他无关依赖版本。
6. Debug 实现不得进入 `src/main` 的 production 依赖图。

#### 验收

- `assembleDebug` 能识别 Debug AIDL。
- `assembleRelease` 不包含 Debug Eval 类。
- 单元测试可以运行一个空的 smoke test。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

### 4.3 Task 1.2：创建 Debug 协议模型与严格校验器

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalAction.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalBridgeState.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalCommand.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalResultEnvelope.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalOperationResult.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalBridgeError.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalTimestamps.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalProtocolCodec.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/protocol/EvalCommandValidator.java`

#### 每个文件职责

- `EvalAction`：声明共享动作枚举，字符串值必须与电脑端 Schema 完全一致。
- `EvalBridgeState`：只允许 `PENDING`、`RUNNING`、`TERMINAL`。
- `EvalCommand`：命令传输 DTO，不承载执行逻辑。
- `EvalResultEnvelope`：统一结果 DTO，容纳 Response、操作结果、状态快照、版本与错误。
- `EvalOperationResult`：表达环境、状态、会话、取消等非 AgentResponse 操作的结果；环境 data 只含 leaseActive/状态摘要，状态 data 含 snapshot，会话 data 含 sessionId/状态，取消 data 含 targetCorrelationId/accepted/status，版本 data 含 versionFingerprint。
- `EvalBridgeError`：包含稳定错误码、阶段、可读消息和可选诊断信息。
- `EvalTimestamps`：记录 commandReceivedAt、startedAt、agentDispatchedAt、callbackReceivedAt、completedAt。
- `EvalProtocolCodec`：JSON 序列化/反序列化；禁止宽松枚举降级。
- `EvalCommandValidator`：执行协议版本、公共字段、动作 payload 和字段组合校验。

#### 校验要求

1. `protocolVersion` 必须在 TestApp 支持范围内。
2. `correlationId` 必须满足电脑端约定的格式和长度，且可安全用于文件名。
3. 未知 action 直接失败。
4. 每个 action 的必填 payload 独立校验。
5. `SEND_TEXT` 不允许 payload 带 `requestId`。
6. `CANCEL_REQUEST` 必须提供 `targetCorrelationId`。
7. 当前 major 的未知字段按电脑端 Pydantic/JSON Schema 策略拒绝；只有共享 Schema 明确声明为可选的 minor 字段才能缺省，TestApp 不自行制定“宽松兼容”。
8. operationResult 按 action 使用判别结构，禁止把 AIAgent 内部 response 或任意 Map 原样塞入 data。
9. `leaseToken/leaseExpiresAtEpochMs` 不得通过 EvalProtocolCodec 序列化到外层 EvalResultEnvelope。

### 4.4 Task 1.3：实现进程级 Eval 模式状态

#### 新建文件

- `app/src/main/java/com/hirain/aiagent/test/EvalModeStateStore.java`

#### 实现要求

这是唯一需要放入 `src/main` 的 Eval 感知小组件，因为正式 `MainActivity` 需要在 Debug Eval 运行期间隔离人工交互；Release 中它保持永远未激活，不依赖任何 Debug 类。

需要提供：

- 原子设置/清除 Eval 活跃状态；
- 查询当前是否有 Eval 运行；
- 查询某个 `clientMessageId` 是否由 Eval 所有；
- 注册 Eval 所有的 `clientMessageId`，并在整个环境租约期间保留；环境 release/manager reset 时统一清理，避免 listener 分发顺序或迟到重复 callback 导致 MainActivity 误显示。
- 进程重启后默认清空，不持久化环境 token。

该类不得保存完整命令、密钥、Prompt 或 Agent 输出。

### 4.5 Task 1.4：实现结构化结果存储

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/store/EvalResultStore.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/store/EvalResultFilePolicy.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/store/EvalResultTransitionGuard.java`

#### 存储路径

第一候选固定为：

```text
<TestApp filesDir>/eval-results/<correlationId>.json
```

电脑端读取方式预期为：

```text
adb exec-out run-as com.hirain.aiagent.test cat files/eval-results/<correlationId>.json
```

#### 写入语义

1. 先写同目录临时文件。
2. 使用 FileOutputStream 写 UTF-8，flush 后调用 `getFD().sync()`。
3. 在同一目录原子 replace 到正式文件名：API 26+ 先尝试 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`，若文件系统不支持 atomic move，则回退到同目录 `android.system.Os.rename`；TestApp minSdk 24 的 API 24–25 直接使用 `Os.rename`。禁止只按 API 等级假定文件系统一定支持 ATOMIC_MOVE。
4. 同一 correlationId 的状态只能按 `PENDING → RUNNING → TERMINAL` 或校验失败的 `PENDING → TERMINAL` 前进。
5. TERMINAL 文件不可被第二个回调、超时线程或重试覆盖。
6. 每次读取后必须能完整反序列化，不能让电脑端看到半个 JSON。
7. 文件操作通过一个最小可注入接口隔离，使 JVM 测试使用临时目录实现，Android 真实 rename/fsync 在设备 smoke test 验证；不要为此引入完整存储框架。

#### 清理策略

Demo 默认：

- 最多保留 200 条；
- 最长保留 7 天；
- 只删除已终态且超过策略的数据；
- 不在一条命令执行过程中删除其文件。

#### Logcat 边界

Logcat 只记录：correlationId、action、bridgeState、错误码和耗时。不得把完整用户输入、完整模型输出或车辆快照作为正式结果输出到 Logcat。

### 4.6 Task 1.5：验证 `run-as` 结果通道

#### 执行步骤

1. 安装 Debug TestApp 到 Android Studio 虚拟机。
2. 由一个临时 Debug smoke 入口写入测试结果文件。
3. 先从 `local.properties` 解析 `sdk.dir` 得到 adb；当前环境可在 PowerShell 使用 `$adb = 'D:\code\android\forSdk\Sdk\platform-tools\adb.exe'`。
4. 执行 `& $adb shell run-as com.hirain.aiagent.test ls files/eval-results`。
5. 执行 `& $adb exec-out run-as com.hirain.aiagent.test cat files/eval-results/<correlationId>.json` 并在电脑端解析 JSON。
6. 验证文件在应用内部不可被其他普通 App 直接访问。

#### 决策门

若模拟器上的 `run-as` 因包不可调试、ROM 策略或签名限制不可用，实施 Agent 必须停止并向用户报告证据，再讨论是否改为 external app-specific debug 目录或 Debug ContentProvider。不得自行增加第二套结果通道。

### 4.7 Phase 1 测试

#### 新建测试

- `app/src/test/java/com/hirain/aiagent/test/eval/protocol/EvalProtocolCodecTest.java`
- `app/src/test/java/com/hirain/aiagent/test/eval/protocol/EvalCommandValidatorTest.java`
- `app/src/test/java/com/hirain/aiagent/test/eval/store/EvalResultTransitionGuardTest.java`
- `app/src/test/java/com/hirain/aiagent/test/eval/store/EvalResultStoreTest.java`

#### 覆盖场景

- 每一种 action 的合法/非法样例。
- 不支持版本、缺少 correlationId、非法文件名字符。
- SEND_TEXT 非法携带 requestId。
- 正常路径 PENDING → RUNNING → TERMINAL，校验失败 PENDING → TERMINAL，其他迁移全部拒绝。
- TERMINAL 后重复 callback/timeout 不覆盖。
- 临时文件失败不污染正式结果。
- 超过数量/时间后的清理。

#### 阶段出口

- Debug 和 Release 均可构建。
- 协议模型导出的样例可通过电脑端 Schema；若电脑端已提供 canonical fixtures，再验证双向解析，不能把可选 fixture 当作 Phase 1 的隐含硬依赖。
- `run-as` 通道在目标模拟器验证通过，或已获得用户批准的替代通道。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat processDebugMainManifest processReleaseMainManifest
.\gradlew.bat assembleDebug assembleRelease
```

Release APK 需要额外检查不含 `com/hirain/aiagent/test/eval/` Debug 类和 EvalBridgeActivity；不能只因为 Release 构建成功就判定隔离完成。

---

## 5. Phase 2：AIAgent Debug 连接、环境租约与半自动入口

### 5.1 阶段目标

完成 TestApp 到 AIAgent Debug Eval Service 的连接，能够通过一个可见的 Debug Activity 人工导入命令、获取环境、重置/应用/读取状态并查看结构化结果。Phase 结束时形成“环境控制 + 结果落盘”半自动子闭环；尚未接入 AgentResponse，不能宣称最小评估闭环完成。

### 5.2 Task 2.1：复制 Debug AIDL 客户端契约

#### 新建文件

- `app/src/debug/aidl/com/hirain/aiagent/eval/IAIAgentEvalDebug.aidl`

#### 实现要求

1. 包名、descriptor 和 `String execute(String requestJson)` 方法签名必须与 AIAgent 的 Debug AIDL 完全一致。
2. 方法使用 JSON 请求/响应字符串，不引入跨项目 Parcelable。
3. AIDL 文件头注明它是 TestApp ↔ AIAgent 的内部 Debug 传输契约，不是电脑端外层 EvalCommand/EvalResultEnvelope Schema。
4. 计划执行时先对比 AIAgent 文件，禁止凭计划文本手写两个不同版本。
5. 内层 protocolVersion 仍使用共享 `{major, minor, schemaHash}` 对象形状；TestApp 不把它降级成单个 int/string。

### 5.3 Task 2.2：实现 AIAgent Debug Service 客户端

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/AIAgentEvalDebugClient.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalDebugServiceConnection.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalDebugProtocolMapper.java`

#### 实现要求

1. 使用显式 ComponentName `com.hirain.aiagent/com.hirain.aiagent.eval.EvalDebugService` 绑定 AIAgent Debug Eval Service。
2. 暴露 connect、disconnect、acquire、reset、apply、read、getVersion、release。
3. Binder 调用放在后台线程，设置明确超时。
4. 处理 service disconnected、binder died、RemoteException 和非法响应。
5. 环境 token 和 leaseExpiresAtEpochMs 只保存在 application-scoped manager 内存，禁止写进结果文件、SharedPreferences 或 Logcat。
6. release 后立即清空本地 token。
7. Activity 销毁/旋转只解除 UI observer，不能释放仍由 application-scoped manager 持有的租约或取消运行命令；只有显式 RELEASE/manager shutdown 才 best-effort release。进程被杀不能依赖 onDestroy，AIAgent TTL 是最终兜底。

### 5.4 Task 2.3：实现环境生命周期协调器

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/environment/EvalEnvironmentManager.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/environment/EvalEnvironmentState.java`

#### 状态建议

```text
IDLE
CONNECTING
ACQUIRING
READY
RELEASING
FAILED
```

#### 实现要求

- 同一 TestApp 进程只允许一个环境租约。
- 未 READY 时拒绝 RESET/APPLY/READ/SEND_TEXT。
- acquire 成功后激活 `EvalModeStateStore`。
- release 成功后清理 token、命令记录并关闭 Eval 模式。
- binder death 时进入 FAILED，禁止继续执行命令。
- 使用 AIAgent response 返回的 leaseExpiresAtEpochMs 记录到期时间，不用本地当前时间猜测；本地 expiry 只用于提前提示/禁止盲目 SEND_TEXT，租约是否有效最终以 AIAgent 下一次 lease-authenticated response 为准。每次 SEND_TEXT 的 before-state READ 会刷新并返回 expiry；不把 token/expiry 暴露给电脑端外层结果。
- 对重复 acquire/release 提供幂等、可解释结果。

### 5.5 Task 2.4：建立命令记录和关联表

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/runtime/EvalExecutionRecord.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/runtime/EvalExecutionRegistry.java`

#### 记录字段

- correlationId；
- action；
- bridgeState；
- requestId（发送后才有）；
- clientMessageId；
- started/completed 时间；
- 是否收到 callback；
- terminal CAS 标志；
- 可取消的 watchdog handle。

#### 实现要求

`correlationId → requestId` 的映射由 TestApp 建立。它既服务于取消，也服务于 callback 归属判断。终态后可保留到本次 Eval 环境释放，随后清理。

### 5.6 Task 2.5：实现统一命令管理器

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/runtime/EvalBridgeManager.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/runtime/EvalCommandDispatcher.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/runtime/EvalBridgeErrorCodes.java`

#### 职责边界

`EvalBridgeManager` 负责总生命周期和依赖装配；`EvalCommandDispatcher` 只负责按 action 分派；具体 AIAgent 调用由各 client/manager 完成。

`EvalBridgeManager` 必须以 Application Context 创建进程级单例，持有一个串行状态执行器、一个仅负责 timeout/retry 触发的 ScheduledExecutor、两个 AIAgent client、EnvironmentManager、ExecutionRegistry 和 ResultStore。Activity 只订阅状态和提交命令，不拥有正在运行任务；屏幕旋转或 Activity 重建不能导致 listener 重复注册、租约释放或请求丢失。ScheduledExecutor 只把事件投递回串行状态执行器，不直接改 ExecutionRecord 或写结果文件。

#### 串行规则

- ACQUIRE/RESET/APPLY/READ/SESSION/GET_VERSION/RELEASE 串行执行。
- SEND_TEXT 在 Demo 默认也串行，避免车辆状态和会话相互污染。
- CANCEL_REQUEST 可以针对正在运行的 SEND_TEXT 并发进入。
- 新命令到达时若已有不兼容命令运行，返回 `BRIDGE_BUSY`，不建立隐式队列。
- 每个命令先完成大小、JSON envelope 和安全 correlationId 的最小校验，再立即创建 PENDING；完整 Schema/payload 校验失败则写 bridgeError TERMINAL，成功才抢占 RUNNING、调用目标 client，并以 AgentResponse/operationResult/bridgeError 形成 TERMINAL。环境和会话 operation 也必须写结果文件，不能只更新 UI。
- SEND_TEXT 调用 `processAgentRequest` 返回后，串行状态执行器必须立即释放事件循环，等待 callback/watchdog 以事件形式重新进入；禁止在该线程阻塞等待 Response。否则 CANCEL_REQUEST、callback 和 watchdog 都可能饿死。
- after-state 重试使用 ScheduledExecutor 定时投递下一次 READ 事件，禁止在串行状态执行器上 `sleep` 或忙等。

### 5.7 Task 2.6：实现半自动 EvalBridgeActivity

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/ui/EvalBridgeActivity.java`
- `app/src/debug/res/layout/activity_eval_bridge.xml`
- `app/src/debug/res/values/strings_eval.xml`
- `app/src/debug/AndroidManifest.xml`

#### 半自动界面最小功能

- 粘贴或导入单条 EvalCommand JSON/Base64。
- 显示解析和校验结果。
- 显示 AIAgent 正式 Service 与 Debug Eval Service 的连接状态。
- 按钮式 acquire、execute、release。
- 显示 correlationId、bridgeState、结果文件路径和错误码。
- 不在界面展示或复制环境 token。

#### Manifest 要求

- Activity 仅存在 Debug manifest。
- `exported=true` 以支持后续 ADB 显式启动，设置 `launchMode=singleTop` 和 `excludeFromRecents=true`，不声明通用隐式 Intent Filter，保证重复 `am start` 进入 onNewIntent。
- 启动后必须校验命令结构，不能把 Intent extras 直接传入业务调用。
- 自动 Intent 解码前限制 Base64/原始 JSON 大小，原始 JSON 最大 16 KiB，与电脑端计划一致；超限写 COMMAND_INVALID，不创建第二传输通道。
- 只有 JSON 可解析且含有格式安全的 correlationId 时，协议错误才能写入对应 `<correlationId>.json`；完全无法解析或 correlationId 不安全时只在 Debug Activity 显示并写脱敏诊断日志，不能为凑结果而生成电脑端无法关联的伪 correlationId。电脑端自身必须在发送前完成 Schema 校验。
- 这是仅供开发模拟器使用的 exported Debug 控制面，不能安装到量产/真实车机环境长期运行；Debug-only 和 Release 缺失是本期安全边界，不能声称包名校验能够阻止其他 App 启动 TestApp Activity。
- Release merged manifest 中不得存在此 Activity。

### 5.8 Phase 2 测试

#### 单元测试

- 环境状态机合法/非法转换。
- acquire/release 幂等。
- token 不出现在序列化结果。
- service disconnect 后命令失败。
- command busy 与取消并发规则。

#### 设备测试

1. 启动 AIAgent Debug 和 TestApp Debug。
2. 人工打开 EvalBridgeActivity。
3. acquire 环境。
4. reset 后 read，检查基线快照。
5. apply 一个合法 Patch，再 read 检查 revision 和字段变化。
6. apply 非法 Patch，检查状态完全未改变。
7. getVersion 获得结构化指纹。
8. release 后确认人工模式恢复。

#### 阶段出口

无需电脑端批量运行即可手工走通“命令 → AIAgent Debug 能力 → 结构化结果文件”的环境控制子闭环；完整的半自动 Eval 闭环以 Phase 3 出口为准。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

设备步骤无法自动关闭时，必须记录每条 operation 的 correlationId、结果文件和期望 snapshot/revision，不能只写“Activity 可用”。

---

## 6. Phase 3：真实 Agent 请求、回调归属、会话与取消

### 6.1 阶段目标

将 SEND_TEXT、会话 CRUD 和取消连接到现有正式 AIAgent Facade，保证评估请求经过真实 Agent 主链路，并且不污染 MainActivity 的人工 UI/TTS 状态。

### 6.2 Task 3.1：封装正式 AIAgent Facade

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalAgentClient.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalAgentResponseObserver.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalAgentRequestFactory.java`
- `app/src/debug/java/com/hirain/aiagent/test/eval/agent/EvalAgentResponseMapper.java`

#### 实现要求

1. 复用现有 `AIAgent.getInstance()` 及多 listener 机制；按当前真实 API 调用 `registerAIAgentLisener`/`unRegisterAIAgentLisener`（保留既有拼写），不为了 Eval 修改 Facade 公共 API。
2. 不复制或重新实现正式 Service 绑定协议。
3. 发送前由 TestApp 生成 UUID requestId。
4. 设置 `clientMessageId = correlationId`。
5. 传入命令中的 userId、sessionId、personaId、text 和允许的 extra context，同时设置 inputType=`TEXT`、sourceApp=`com.hirain.aiagent.test` 和当前 timestamp；不设置 IMAGE/VOICE 字段。
6. 将 requestId/clientMessageId 先登记到 Registry，再调用 Facade，避免同步失败或极快 callback 丢失关联。
7. 检查 `processAgentRequest` 的 int 返回值：0 才进入等待 callback；-1 立即形成 AGENT_SERVICE_UNAVAILABLE bridgeError、清理 ID/watchdog，不能继续假装 RUNNING。
8. 保留 AIAgent 原始 status、errorType/errorDetail 和最终文本，不二次改写业务语义；字段名称以当前 AgentResponse 实际 getter 为准，不凭计划虚构 errorCode。
9. AIDL callback 只复制必要 Response 并投递到 EvalBridgeManager 单线程执行器，立即返回 Binder 线程；不得在 callback 内同步读取状态、写文件或等待锁。

### 6.3 Task 3.2：实现 SEND_TEXT 完整状态流

#### 执行顺序

1. 校验环境 READY、命令合法、无冲突请求。
2. 立即创建 ExecutionRecord 并写 PENDING；后续 before/version/发送任一步失败都必须有可读取的 bridgeError 终态。
3. 以 CAS 进入 RUNNING，读取 beforeVehicleState 和 versionFingerprint。
4. 创建 requestId，将 clientMessageId=request correlationId 注册为 Eval 所有，并先写入 Registry。
5. 调用正式 `processAgentRequest`，检查同步返回值。
6. 收到 callback 后同时核对 requestId 和 clientMessageId，并把处理投递到 manager 单线程执行器。
7. 短轮询 READ_STATE，等待 AIAgent EvalRequestPermit/in-flight 归零后读取 afterVehicleState；普通完成通常立即可读，取消/超时可能稍晚。
8. 组装真实 AgentResponse、状态、版本、时间戳。
9. CAS 抢占 TERMINAL 并原子写文件，取消 watchdog；Eval clientMessageId 保留到环境 release，以过滤迟到/重复 callback。

#### 竞态要求

必须处理“Facade 调用返回之前 callback 已到达”的情况。ExecutionRecord 和终态 CAS 必须先建立；RUNNING 写入与 callback 不能互相覆盖。重复 callback 仅记录诊断，不改终态。after-state 轮询只接受 AGENT_BUSY 作为短暂状态，建议最多等待 2 秒、100 ms 间隔；超时后保留真实 AgentResponse，但以 AFTER_STATE_UNAVAILABLE bridge warning/error 标记结果不可做状态评分，不得使用 beforeState 伪装 afterState。

### 6.4 Task 3.3：实现请求 watchdog

#### 规则

- TestApp watchdog 必须略大于 AIAgent 的端到端 30 秒 deadline，Demo 建议默认 40 秒并配置为 Debug 常量。
- SEND_TEXT 的 command.timeoutMs 只允许在 35–60 秒范围覆盖 bridge watchdog，缺省为 40 秒；它不改变 AIAgent 内部 30 秒 deadline。控制/会话 Binder 调用使用独立较短超时，不能共用 SEND_TEXT watchdog。
- watchdog 触发时先调用正式 cancel。
- 等待一个短 grace period 接收 AIAgent 的真实取消/超时响应。
- grace 后仍无 Response，TestApp 才以 `AGENT_RESPONSE_MISSING` 形成桥接错误终态。
- TestApp 不得伪造 Agent 的 `TIMEOUT` AgentResponse。
- 正常 callback 到达后取消 watchdog。

### 6.5 Task 3.4：实现 CANCEL_REQUEST

#### 输入语义

电脑端提供：

```text
targetCorrelationId
```

TestApp 执行：

1. 在 Registry 中查找目标 record。
2. 读取由 TestApp 生成的真实 requestId。
3. 调用正式 Facade 的取消方法。
4. 按当前真实 API 调用 `cancelAgentRequest(requestId, reason)`，将返回的 `CancelRequestResult` 映射到当前 CANCEL 命令的 `operationResult`。
5. 被取消 SEND_TEXT 的最终状态仍由它自己的 AgentResponse 或 watchdog 决定。

取消命令和目标请求分别拥有自己的 correlationId 与结果文件。

### 6.6 Task 3.5：实现会话动作

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/session/EvalSessionController.java`

#### 实现要求

- CREATE_SESSION 按当前真实 API 调用 `createConversation(ConversationRequest)`，并从 `ConversationOperationResult` 返回真实 sessionId/操作状态。
- SWITCH_SESSION 按当前真实 API 调用 `switchConversation(userId, sessionId)`，使用命令中的明确 userId/sessionId，不能依赖 MainActivity 当前选择。
- DELETE_SESSION 按当前真实 API 调用 `deleteConversation(userId, sessionId)` 并返回真实 `ConversationOperationResult`。
- 所有动作写 `operationResult`，不伪装成 AgentResponse。
- 环境释放前可按命令 metadata 指示清理临时 Eval 会话；默认不擅自删除电脑端要求保留的会话。
- Demo 不扩展会话列表同步或复杂会话模板。

### 6.7 Task 3.6：隔离 MainActivity 人工交互

#### 修改文件

- `app/src/main/java/com/hirain/aiagent/test/activity/MainActivity.java`

#### 最小修改要求

1. 在发送文本、切换/删除会话、用户切换、ASR 启动等会影响 Eval 的入口处检查 `EvalModeStateStore`。
2. Eval 活跃时禁用或友好提示人工操作，防止并发污染。
3. `onAIResponse` 的第一段逻辑先按 response.clientMessageId 查询 EvalModeStateStore；命中时立即 return，不得先执行 `requestTtsMap.remove`、current/迟到判断、UI 或 TTS。Eval ID 保留到环境 release，不能在 Eval observer 收到 callback 后立刻删除。
4. Eval Response 仍由注册的 Eval observer 接收，不能在 MainActivity 中吞掉全局 listener 回调。
5. Eval 结束后恢复原有交互。
6. 不重构现有 UI、语音、会话和普通请求状态机。

### 6.8 Task 3.7：稳定错误码

至少定义并测试：

| 错误码 | 含义 |
|---|---|
| `COMMAND_INVALID` | 命令或 payload 校验失败 |
| `PROTOCOL_VERSION_UNSUPPORTED` | 协议版本不支持 |
| `BRIDGE_BUSY` | 当前存在冲突命令 |
| `AGENT_SERVICE_UNAVAILABLE` | 正式 AIAgent Service 不可用 |
| `EVAL_SERVICE_UNAVAILABLE` | Debug Eval Service 不可用 |
| `ENVIRONMENT_NOT_READY` | 未持有有效环境租约 |
| `ENVIRONMENT_LEASE_LOST` | token 过期、被拒绝或 Binder 断开 |
| `RESULT_STORE_FAILED` | 结果文件无法可靠写入 |
| `AGENT_RESPONSE_MISSING` | watchdog 与 grace 后仍无真实响应 |
| `AFTER_STATE_UNAVAILABLE` | 收到 AgentResponse 后，AIAgent 实际执行仍未静止或状态读取失败 |
| `REQUEST_MAPPING_MISSING` | 取消或 callback 无法关联 |
| `BRIDGE_PROCESS_RESTARTED` | 进程恢复时发现未完成命令 |
| `INTERNAL_BRIDGE_ERROR` | 未分类的桥接实现错误 |

错误 detail 可以包含异常类型和简短消息，但不得包含 API Key、环境 token 或未脱敏 Prompt。

### 6.9 Phase 3 测试

#### 单元/替身测试

- requestId 在 TestApp 生成且电脑命令不能指定。
- correlationId 正确映射为 clientMessageId。
- callback 同时核对两个 ID。
- callback 早到、重复到达、watchdog 与 callback 竞争。
- CANCEL 使用 targetCorrelationId 查找 requestId。
- 会话动作产生 operationResult。
- MainActivity gate 对 Eval ID 和普通 ID 的区分。

#### 设备集成测试

1. 发送普通问答，检查真实 AgentResponse 和 Trace ID。
2. 发送会触发车控工具的请求，比较 before/after snapshot。
3. 发送需要二次确认的请求，验证后续请求仍保持同一 Eval 会话。
4. 发起慢请求后按 targetCorrelationId 取消。
5. 模拟 AIAgent Service 断连，检查结构化桥接错误。
6. Eval 期间尝试 MainActivity 人工发送/语音/切会话，确认被隔离。
7. Eval 结束后普通 UI、TTS、ASR 和会话操作恢复。

#### Trace 人工验收

在 Phoenix 中用 requestId/clientMessageId 验证：

- 真实 Runtime/Context/AgentLoop/Tool spans 存在；
- `client_message.id` 等于 correlationId；
- `request.id` 等于 Response 中真实 requestId；
- Eval 环境标记和车辆状态 revision 可见；
- Trace 中不出现环境 token。

### 6.10 Phase 3 出口

半自动 Activity 已可完成“准备环境 → 正式 Agent 请求 → Response/状态/版本结构化落盘 → Phoenix 人工关联”的完整 Demo 评估闭环。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

完成门还要求至少一条真实低风险 TEXT、一次取消和一次 MainActivity 响应过滤人工验证；只通过 JVM 测试不能宣称半自动闭环完成。

---

## 7. Phase 4：ADB 自动触发、恢复机制与三方验收

### 7.1 阶段目标

在不增加 Receiver、后台常驻 Service 或 UIAutomator 的前提下，让电脑端通过 ADB 启动同一个 EvalBridgeActivity，并验证进程恢复、文件清理、Release 隔离和三方协议一致性。

### 7.2 Task 4.1：为同一 Activity 增加自动模式

#### Intent 约定

```powershell
$adb = 'D:\code\android\forSdk\Sdk\platform-tools\adb.exe'
$commandB64 = '<BASE64_URL_SAFE_NO_WRAP>'
& $adb shell am start `
  -n com.hirain.aiagent.test/com.hirain.aiagent.test.eval.ui.EvalBridgeActivity `
  --es eval_command_b64 $commandB64 `
  --ez eval_auto_execute true
```

#### 实现要求

- `eval_auto_execute=false` 或缺省：解析后等待人工确认。
- `eval_auto_execute=true`：校验成功后自动执行；若完全无法解析或缺少安全 correlationId，按 Phase 2 的拒绝规则只给出 Debug 诊断，不执行副作用。
- Base64 使用 URL-safe、no-wrap 规则，并以 UTF-8 解码 JSON。
- 接受带或不带 `=` padding 的 URL-safe Base64，但解码后的原始 JSON 最大 16 KiB；超限不执行任何动作。
- Activity 已运行时通过 `onNewIntent` 处理新命令。
- correlationId 已存在终态文件时，返回现有终态，不重复执行副作用。
- correlationId 对应 RUNNING 时返回 busy/当前状态，不重复发送请求。
- 无效 Intent 只生成结构化错误，不导致 Activity 崩溃。

### 7.3 Task 4.2：进程恢复与陈旧任务处理

#### 新建文件

- `app/src/debug/java/com/hirain/aiagent/test/eval/recovery/EvalBridgeRecovery.java`

#### 实现要求

1. 启动时扫描结果目录。
2. application-scoped manager 在新进程首次初始化时，所有遗留 PENDING/RUNNING 都来自已丢失内存 Registry 的旧进程，立即转为 `BRIDGE_PROCESS_RESTARTED` 终态；Activity 重建但进程未重启时不得重复扫描或误判当前任务。
3. 不尝试恢复旧环境 token。
4. 新一轮执行必须重新 acquire、reset/apply。
5. 恢复逻辑遵守终态不可覆盖。
6. 进程退出前 best-effort release；AIAgent 自身 TTL 是最终兜底。

### 7.4 Task 4.3：补齐可诊断状态

#### 可观察信息

- 当前 bridge state；
- AIAgent 正式/Debug Service 是否连接；
- 当前 correlationId/action；
- 结果文件绝对应用内相对路径；
- 最近稳定错误码；
- 关键阶段耗时。

不得增加 HTTP 调试服务器、WebSocket 或开放 ContentProvider。

### 7.5 Task 4.4：三方协议契约测试

#### TestApp 侧新建测试

- `app/src/test/java/com/hirain/aiagent/test/eval/contract/EvalPcFixtureContractTest.java`
- `app/src/test/java/com/hirain/aiagent/test/eval/contract/EvalAgentDebugContractTest.java`

#### 测试要求

1. 读取由 AIAgent_Eval 仓库导出的 canonical command fixtures。
2. TestApp 解析后再序列化，验证关键字段不漂移。
3. TestApp result fixtures 能被电脑端 Schema 接受。
4. AIAgent Debug request/response 的动作、版本、错误码与 TestApp mapper 对齐。
5. 对每次协议升级维护明确 version fixture，不只测试最新版本。

如果不希望跨仓库复制 fixture，可由电脑端在三方验收脚本中动态生成；TestApp 仓库只保留最小 canonical 样例。Demo 不建立新的共享发布仓库。

### 7.6 Task 4.5：Release 隔离验证

必须检查 Release merged manifest、APK 内容和运行行为：

- 没有 EvalBridgeActivity。
- 没有 IAIAgentEvalDebug AIDL 生成类。
- 没有 eval Debug 包代码和资源。
- 普通 MainActivity 在 `EvalModeStateStore` 永未激活时行为与改造前一致。
- Release 不接受 ADB Eval Intent。

### 7.7 Task 4.6：电脑端协同验收

由电脑端 AIAgent_Eval 按顺序执行最小套件：

1. 部署/检查 Debug APK 与协议版本。
2. ACQUIRE_ENVIRONMENT。
3. RESET_STATE。
4. APPLY_STATE 并 READ_STATE。
5. CREATE_SESSION/SWITCH_SESSION。
6. SEND_TEXT 普通问答。
7. SEND_TEXT 车控请求并校验状态变化。
8. SEND_TEXT 后 CANCEL_REQUEST。
9. GET_VERSION。
10. DELETE_SESSION（按用例策略）。
11. RELEASE_ENVIRONMENT。
12. `run-as` 拉取每个 correlationId 的结果。
13. 从 Phoenix 按 requestId/clientMessageId 读取 Trace。
14. 电脑端执行 deterministic checks 和配置化 LLM Judge。

TestApp 的完成标准止于第 12 步数据可靠可读以及第 13 步所需 ID 完整；评分与报告属于 AIAgent_Eval。

### 7.8 Phase 4 测试

#### 自动化场景

- ADB 自动执行成功。
- 重复投递相同 correlationId 不重复副作用。
- Activity 重建/onNewIntent 正确。
- TestApp 进程在 RUNNING 中被杀后产生恢复错误。
- token 过期后拒绝继续执行。
- 200 条/7 天清理策略。
- 多条命令串行运行不发生状态污染。

#### 回归场景

- 人工文字聊天。
- TTS 与 ASR。
- 创建、切换、删除会话。
- 正常取消与超时。
- AIAgent 未安装或未启动时的提示。

#### 阶段出口

- 电脑端可通过 ADB 驱动同一桥接入口。
- 结果文件稳定、可重复读取。
- Phoenix Trace 可稳定关联。
- Release 不包含 Eval 能力。
- TestApp 原有人工调试功能无明显回归。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat processDebugMainManifest processReleaseMainManifest
.\gradlew.bat assembleDebug assembleRelease
```

设备/Phoenix/电脑端联调未完成时，状态只能写“代码与构建完成，三方验收未关闭”；不得用 Release manifest 检查替代真实 run-as/Trace 关联。

---

## 8. 文件级实施清单

### 8.1 `src/main` 最小修改

| 文件 | 操作 | 边界 |
|---|---|---|
| `EvalModeStateStore.java` | 新建 | 只保存进程级 Eval 活跃/ID 归属，不依赖 Debug 类 |
| `activity/MainActivity.java` | 小范围修改 | 人工入口 gate 与 Eval Response UI/TTS 过滤，不重构原逻辑 |

### 8.2 `src/debug` 主要新增

| 目录 | 主要职责 |
|---|---|
| `debug/aidl/.../IAIAgentEvalDebug.aidl` | AIAgent Debug Service 客户端契约 |
| `eval/protocol/` | Schema 的 Android DTO、codec、validator |
| `eval/store/` | 原子结果文件与状态转换 |
| `eval/agent/` | 正式 Agent Facade 和 Debug Eval Service 两类客户端 |
| `eval/environment/` | 租约生命周期 |
| `eval/runtime/` | 命令分派、执行记录、关联和终态竞争 |
| `eval/session/` | 会话动作适配 |
| `eval/ui/` | 半自动/自动共用 Activity |
| `eval/recovery/` | 进程恢复与陈旧任务收敛 |

### 8.3 测试文件

测试应覆盖协议、存储、租约、关联、竞态、取消、恢复和跨项目 fixtures。设备验证记录建议保存到 AIAgent 主仓库的 `docs/testresult/eval/`，避免三个仓库各写一份互相冲突的验收结论。

---

## 9. Goal 模式任务边界

### 9.1 每个 Phase 的执行规则

后续子 Agent 每次只领取一个 Phase，并严格按以下顺序工作：

1. 重新读取本计划、对应设计大纲和前序 Phase 结果。
2. 检查目标仓库实时源码与工作树，不假设文件仍与计划编写时一致。
3. 列出本 Phase 的准确修改文件和既有用户改动。
4. 实现本 Phase，不提前实现后续 Phase 的扩展项。
5. 运行本 Phase 单元测试、构建和必要设备测试。
6. 将无法在当前环境完成的设备/Phoenix 验证转成人工验收清单。
7. 明确区分“主路径已实现”和“严格验收已闭环”。

### 9.2 必须暂停询问用户的情况

- `run-as` 在目标模拟器不可用，需要更换结果通道。
- AIAgent Debug AIDL 实际签名与本计划不一致，需要改变共享协议。
- TestApp 的签名/包名导致 AIAgent 调用方校验无法通过。
- 需要修改正式 AIAgent AIDL 或正式 SDK JAR。
- 需要新增网络服务、ContentProvider、Receiver、后台 Service 或 UIAutomator。
- 电脑端 Schema 对必填字段、版本策略或错误码有冲突定义。
- 现有 TestApp 用户改动与计划目标发生直接冲突。

### 9.3 禁止扩张

- 不把评分逻辑放进 TestApp。
- 不把 Phoenix SDK/客户端放进 TestApp。
- 不做通用任务队列和多并发 Eval。
- 不持久化环境 token。
- 不让电脑端控制 requestId。
- 不用截图/OCR 读取结果。
- 不以 Logcat 代替结构化结果文件。

---

## 10. 最终完成标准

TestApp 改造只有同时满足以下条件才算完成：

1. Debug 包能获取并释放 AIAgent Eval 环境租约。
2. 可原子重置、应用和读取虚拟车辆状态。
3. SEND_TEXT 走正式 AIAgent 主链路。
4. correlationId/clientMessageId/requestId 所有权与映射稳定。
5. AgentResponse、operationResult、beforeVehicleState/afterVehicleState、versionFingerprint 和错误可结构化落盘。
6. CANCEL_REQUEST 通过 targetCorrelationId 解析真实 requestId。
7. MainActivity 不展示或播报 Eval Response，人工操作不会污染 Eval。
8. 半自动 Activity 可独立完成最小闭环。
9. 同一 Activity 可被 ADB 自动触发，重复命令幂等。
10. 进程中断、Binder 断开、watchdog、重复 callback 均有确定终态。
11. 电脑端可用 `run-as` 读取完整 JSON；若改用替代通道，已有用户明确批准和文档更新。
12. Phoenix 可用真实 requestId 和 correlationId/clientMessageId 关联 Trace。
13. Release APK 不包含 Debug Eval 入口或协议实现。
14. TestApp 原有文字、语音、会话与取消能力通过回归测试。
15. 串行状态执行器不阻塞等待 callback/after-state，CANCEL_REQUEST 和 watchdog 不会因线程饥饿失效。
16. 外层时间格式、嵌套 AgentResponse 原始 timestamp 和 lease expiry 的三种语义不会混用。

---

## 11. 推荐实施顺序摘要

```text
Phase 1：协议 + 原子结果存储 + run-as 决策门
    ↓
Phase 2：AIAgent Debug 客户端 + 环境租约 + 半自动 Activity
    ↓
Phase 3：真实 Agent 请求 + Response/状态采集 + 会话/取消 + UI 隔离
    ↓
Phase 4：同入口 ADB 自动化 + 恢复 + 三方契约 + Release 验收
```

该顺序保证最早获得可验证的半自动闭环，同时让后续自动化建立在已经稳定的协议、环境隔离和结果存储之上。
