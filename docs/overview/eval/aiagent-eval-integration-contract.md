# AIAgent Eval 接入契约（供 TestApp 与电脑端开发）

> **适用范围：** AIAgent 当前已落地的 Debug Eval 适配。本文描述的是实际代码行为，而非未来计划。
>
> **阅读对象：** `AIAgentTestApp` 的 Eval Bridge、电脑端 `AIAgent_Eval` 的协议/ADB/结果处理模块。
>
> **版本：** 2026-07-17；内部 Debug protocol major 为 `1`。

---

## 1. 三端职责与事实边界

```text
电脑端 AIAgent_Eval
  └─ 外层 EvalCommand / EvalResultEnvelope（唯一共享 Schema）
       │ ADB / Activity / 文件结果通道
       ▼
Android AIAgentTestApp（Bridge，尚由 TestApp 项目实现）
  ├─ correlationId → clientMessageId
  ├─ 保存 leaseToken（仅内存）
  ├─ 调用主 AIAgent AIDL 发 TEXT
  └─ 调用本文件描述的 Debug AIDL 做环境控制
       │ Binder，同一设备
       ▼
Android AIAgent
  ├─ 主 AIDL：真实 AgentRequest → AgentResponse
  └─ Debug AIDL：环境租约与车辆状态控制
```

三类数据不可互相替代：

| 数据 | 权威方 | 用途 | 不应承担的职责 |
|---|---|---|---|
| `AgentResponse` | AIAgent 主业务 AIDL | 用户可见最终响应、requestId、成功/错误 | 不携带环境快照或租约 token |
| `VehicleStateSnapshot` | AIAgent Debug AIDL | case 前后状态、revision | 不代表 LLM 最终文本或 Safety 解释 |
| Phoenix Trace | AIAgent Trace | 关联、模型/Tool/安全观测证据 | 不作为结果传输通道，不记录 leaseToken |

电脑端外层协议的唯一来源仍是 `AIAgent_Eval/schemas/`。本文中的 JSON 是 **TestApp ↔ AIAgent 内部协议**，不能让电脑端直接消费，也不能把内部 `leaseToken` 写进 Dataset、结果文件或 Logcat。

---

## 2. 可绑定入口与前置条件

### 2.1 Debug Service

| 项目 | 值 |
|---|---|
| AIAgent 包名 | `com.hirain.aiagent` |
| Service 完整类名 | `com.hirain.aiagent.eval.EvalDebugService` |
| AIDL 接口 | `com.hirain.aiagent.eval.IAIAgentEvalDebug` |
| Binder 方法 | `String execute(String requestJson)` |
| Bind 方式 | 显式 `ComponentName`，无 action、无 intent-filter |
| 进程 | 与 `AIAgentService` 同进程；共享唯一 VehicleStateMachine 和 EvalEnvironmentRegistry |
| 构建边界 | 仅 `src/debug` 合并；Release APK 没有该 Service 或 AIDL 入口 |

TestApp 必须以显式组件绑定：

```java
new ComponentName(
    "com.hirain.aiagent",
    "com.hirain.aiagent.eval.EvalDebugService"
)
```

### 2.2 调用方门禁

Debug Binder 在每次 `execute` 调用时读取 `Binder.getCallingUid()`，仅当 UID 中包含以下 **可调试** App 才受理：

```text
packageName = com.hirain.aiagent.test
ApplicationInfo.FLAG_DEBUGGABLE = true
```

否则返回结构化 `CALLER_NOT_ALLOWED`，不会抛出解析堆栈或泄露签名信息。因此 TestApp 必须安装 Debug variant；电脑端不能绕过 TestApp 直接 Binder 调用。

### 2.3 Service 就绪状态

- `GET_VERSION` 不依赖主 AIAgent Service 已初始化。
- 其他 operation 依赖 `AIAgentService` 已创建并在 Debug 进程安装 coordinator；未就绪时返回 `AGENT_NOT_READY`。
- 主 Service 销毁时会先卸载 coordinator 并使租约失效；TestApp 必须把该错误当作一次新的绑定/初始化流程，而非重试旧 token。

---

## 3. 内部 Debug JSON 协议

### 3.1 请求对象

请求是 UTF-8 JSON 字符串，最大 **128 KiB**。根对象和 `protocolVersion` 都禁止未知字段。

```json
{
  "protocolVersion": {
    "major": 1,
    "minor": 0,
    "schemaHash": "4cfb198980ae0f4074782100e3b1e02fb6eb8bc336b9aacd9709f54b98eb5fe2"
  },
  "operation": "ACQUIRE_ENVIRONMENT",
  "correlationId": "pc-case-0001",
  "leaseToken": "仅已有租约的后续操作填写",
  "ttlMs": 120000,
  "statePatch": {
    "systems": {
      "speed": { "vehicleSpd": 0 }
    }
  }
}
```

| 字段 | 类型/限制 | 说明 |
|---|---|---|
| `protocolVersion.major` | 整数，必须为 `1` | 不兼容时为 `PROTOCOL_VERSION_UNSUPPORTED` |
| `protocolVersion.minor` | 整数，可省略 | 当前记录为诊断信息，不参与拒绝 |
| `protocolVersion.schemaHash` | 字符串，可省略 | 当前记录为诊断信息；推荐传电脑端 Schema 值 |
| `operation` | 固定枚举 | 见下一节 |
| `correlationId` | 字符串，最长 256 | Acquire 的 case 关联值；不等于主 AIAgent `requestId` |
| `leaseToken` | 字符串，最长 512 | AIAgent 生成的内部能力凭据；除 Acquire 外的环境操作必填 |
| `ttlMs` | 整数，可省略 | 仅 Acquire 使用；60,000–600,000 ms，默认 120,000 ms |
| `statePatch` | object，可省略 | 仅 Apply 使用；可写成 `{systems:{...}}` 或直接 `{speed:{...}}` |

`statePatch` 的未知 system/field、类型不匹配、整数越界或枚举非法会在状态机层返回结构化错误，且不会部分写入。

### 3.2 operation 与调用顺序

| operation | 是否需要 token | 成功结果 | 说明 |
|---|---:|---|---|
| `GET_VERSION` | 否 | `versionFingerprint` | 建议每次 run 起始调用 |
| `ACQUIRE_ENVIRONMENT` | 否 | token、expiry、当前 snapshot | 仅环境空闲且无租约时成功 |
| `RESET_STATE` | 是 | reset 后 snapshot | 运行中请求时拒绝 |
| `APPLY_STATE` | 是 | Patch 后 snapshot | 全量验证后原子写入 |
| `READ_STATE` | 是 | 当前 snapshot | 有 Eval TEXT 正在执行时返回 `AGENT_BUSY` |
| `RELEASE_ENVIRONMENT` | 是 | `OK` 与 snapshot | 有 in-flight 请求时拒绝 |

推荐 TestApp Bridge 顺序：

```text
GET_VERSION
→ ACQUIRE_ENVIRONMENT(correlationId, ttlMs)
→ RESET_STATE
→ [可选] APPLY_STATE
→ 主 AIDL 发送一条 TEXT（clientMessageId = correlationId）
→ 等待 AgentResponse
→ READ_STATE；若 AGENT_BUSY，则短间隔重试 READ
→ 生成外层 EvalResultEnvelope（剥离 token）
→ RELEASE_ENVIRONMENT
```

不要在收到 `AgentResponse` 的瞬间假设状态已经稳定：回调可早于 Agent worker 最终清理与 permit 释放，因此 `READ_STATE` 返回 `AGENT_BUSY` 时应重试，而非记录中间状态。

### 3.3 响应对象

```json
{
  "protocolVersion": {
    "major": 1,
    "minor": 0,
    "schemaHash": "4cfb198980ae0f4074782100e3b1e02fb6eb8bc336b9aacd9709f54b98eb5fe2"
  },
  "success": true,
  "status": "OK",
  "errorCode": "OK",
  "leaseToken": "内部字段，TestApp 仅内存保存",
  "leaseExpiresAtEpochMs": 1760000000000,
  "snapshot": { "...": "..." },
  "versionFingerprint": { "...": "..." }
}
```

Gson 默认不输出 `null` 字段。`status` 为 `OK` 或 `ERROR`；程序判断以 `success` 和稳定 `errorCode` 为准，`errorDetail` 仅供人读。

成功的租约操作会返回刷新后的 `leaseExpiresAtEpochMs`；TestApp 必须以该值为准，不自行推导过期时间。失败写操作当前可能不携带 token/expiry，Bridge 不应因此清空仍有效的本地 token，除非错误明确表示租约失效或未找到。

### 3.4 错误码

| errorCode | Bridge 应对 |
|---|---|
| `CALLER_NOT_ALLOWED` | 安装/绑定 Debug TestApp；不要重试 |
| `PAYLOAD_TOO_LARGE`、`INVALID_REQUEST`、`UNKNOWN_OPERATION` | 本地请求构造错误，结束该 operation |
| `PROTOCOL_VERSION_UNSUPPORTED` | 停止 run，升级/对齐协议 major |
| `AGENT_NOT_READY` | 确认 AIAgent 主 Service 已启动后重新绑定/Acquire |
| `AGENT_BUSY` | Acquire/状态操作：退避重试或结束 case；READ：短轮询 |
| `LEASE_ALREADY_HELD` | 不得覆盖本地 token；等待释放/过期 |
| `LEASE_NOT_FOUND` | 清空本地 token，重新 Acquire；当前实现过期租约会清理后以此码返回 |
| `LEASE_OWNER_MISMATCH`、`LEASE_TOKEN_INVALID` | 视为 Bridge 状态错误，清空 token 并终止当前 run |
| `NO_CHANGES`、`UNKNOWN_STATE_PATH`、`TYPE_MISMATCH`、`VALUE_OUT_OF_RANGE`、`INVALID_ENUM`、`STATE_OPERATION_FAILED` | case 环境设置失败；不得继续发送 TEXT |

当前实现直接返回的主要状态码包括：`OK`、`AGENT_NOT_READY`、`CALLER_NOT_ALLOWED`、`PROTOCOL_VERSION_UNSUPPORTED`、`PAYLOAD_TOO_LARGE`、`INVALID_REQUEST`、`UNKNOWN_OPERATION`、`AGENT_BUSY`、`LEASE_ALREADY_HELD`、`LEASE_NOT_FOUND`、`LEASE_OWNER_MISMATCH`、`LEASE_TOKEN_INVALID`、`NO_CHANGES`、`UNKNOWN_STATE_PATH`、`TYPE_MISMATCH`、`VALUE_OUT_OF_RANGE`、`INVALID_ENUM`、`STATE_OPERATION_FAILED`。

---

## 4. 车辆状态快照与 Patch

### 4.1 Snapshot 统一形状

```json
{
  "schemaVersion": "1.0",
  "environmentRevision": 3,
  "capturedAt": "2026-07-17T08:00:00Z",
  "systems": {
    "speed": { "vehicleSpd": 0 },
    "door": { "doorLocked": false }
  }
}
```

| 字段 | 含义 |
|---|---|
| `schemaVersion` | 当前为 `1.0` |
| `environmentRevision` | 单调环境版本；成功 reset/Patch 会递增，Tool 状态变更在下一次快照观察时反映为新版本 |
| `capturedAt` | UTC ISO-8601 时间，不是 epoch millis |
| `systems` | 固定八个一级键：`ac`、`door`、`window`、`seat`、`speed`、`chassis`、`fragrance`、`dms` |

快照是不可变复制品。电脑端可直接使用 `VehicleStateSnapshot` 校验共享 vehicle-state Schema；但 AIAgent 内部 Debug response 仍不等于电脑端外层结果信封。

### 4.2 可读写字段

| system | 字段 |
|---|---|
| `ac` | `acStatus`, `acDriveTemp`, `acAssistTemp`, `acFanIntensity`, `acEcoMode`, `acAnionStatus`, `acCleanMode`, `acCycMode`, `acDriveSweepAuto`, `acAssistSweepAuto`, `acDriveLeftAirOutlet`, `acDriveRightAirOutlet`, `acAssistAirOutletMode`, `acAssistLeftAirOutlet`, `acAssistRightAirOutlet` |
| `door` | `doorFlOpen`, `doorFrOpen`, `doorRlOpen`, `doorRrOpen`, `doorLocked` |
| `window` | `windowFlOpen`, `windowFrOpen`, `windowRlOpen`, `windowRrOpen`, `windowTopOpen`, `sunShadowOpen`, `windowFDefrosting`, `windowRHeat`, `mirrorLHeat`, `mirrorRHeat`, `noWindowOpeningPassengers` |
| `seat` | `seatFlHeat`, `seatFrHeat`, `seatRlHeat`, `seatRrHeat`, `seatFlAir`, `seatFrAir`, `seatRlAir`, `seatRrAir`, `seatMassageMode`, `seatMassageIntensity`, `steeringHeat` |
| `speed` | `vehicleSpd` |
| `chassis` | `chassisMode` |
| `fragrance` | `fragType`, `fragIntensity` |
| `dms` | `dmsDriveFatigue`, `dmsDriveDistractionLevel`, `dmsDriveEmotion` |

校验范围：温度 16–31、风量 1–7、窗口/座椅通风百分比 0–100、车速 0–240。字符串字段只能使用 AIAgent 当前既有枚举值；建议 TestApp/电脑端从返回 snapshot 建立 fixture，不要自行翻译中文枚举。

一个合法 Patch 示例：

```json
{
  "systems": {
    "speed": { "vehicleSpd": 0 },
    "door": { "doorLocked": false },
    "ac": { "acStatus": true, "acDriveTemp": 24 }
  }
}
```

Patch 采用“字段出现即写入”的语义，因此 `false` 和 `0` 都是有效显式值；未知字段、错误类型或任意字段校验失败时，所有字段与 revision 保持操作前状态。

---

## 5. 主 Agent 请求、租约与关联 ID

### 5.1 ID 所有权

| ID | 所有者 | 规则 |
|---|---|---|
| `correlationId` | 电脑端 Eval | 每个 operation/case 的外层关联值 |
| `clientMessageId` | TestApp Bridge | 对 Eval TEXT 必须设为对应 `correlationId` |
| `requestId` | AIAgent 主业务 | TestApp 不应把电脑端 correlationId 充当 requestId；缺失时 AIAgent 生成 UUID |
| `leaseToken` | AIAgent Debug coordinator | 仅 TestApp 内存保存，绝不出 Android/外层结果 |
| Phoenix `traceId` | OpenTelemetry | 作为观测证据记录，不参与请求控制 |

### 5.2 租约隔离规则

- Acquire 仅在没有活动 TEXT 请求、没有主动/被动场景执行且没有已有租约时成功。
- 单租约 owner 由 **TestApp calling UID** 决定，而不是 correlationId。
- token 为 AIAgent 用 SecureRandom 生成的 128-bit URL-safe 值。
- 租约默认 120 秒；每次成功的租约状态操作、以及 owner TEXT 开始时都会刷新到默认 TTL。
- 租约存续期间：非 owner UID 的主请求被拒绝；owner UID 的非 TEXT（IMAGE/VOICE/CONTROL）也被拒绝；只有 owner UID 的 TEXT 可进入 Agent Runtime。
- AIAgent 无法区分同 UID 下的“人工 TestApp 聊天”和 Eval Bridge，所以 **TestApp 必须在持有租约时关闭人工聊天及非 TEXT 入口**。
- Eval TEXT 的 permit 直到 worker 完成 Trace/HTTP/Session 清理并释放 ActiveRequest 后才关闭。TTL 在 in-flight 期间到期不会交给其他 UID；最后一个 permit 关闭后才真正清租约。

### 5.3 主 AIDL 发送 TEXT 的必要字段

TestApp 仍使用现有主 AIAgent SDK/AIDL `processAgentRequest(AgentRequest)`，不是 Debug AIDL 发送文本。Eval Bridge 至少设置：

```text
inputType       = "TEXT"
text            = case 输入
clientMessageId = correlationId
sourceApp       = TestApp 自身标识
requestId       = 留空，由 AIAgent 生成真实 requestId
```

收到 `AgentResponse` 后，TestApp 应按 `clientMessageId` 找回 case，再保存真实 `requestId`、`success`、`text`、`errorType`、`errorDetail`、`timestamp`。其中嵌套 `AgentResponse.timestamp` 是 Android epoch-millis；不要把它误写为外层 ISO 时间字段。

---

## 6. Trace 与 Phoenix 调试

每个 TEXT 根 span（包括 ActiveRequest BUSY/重复与 Eval 租约 BUSY）会写入：

| Trace 属性 | 值 |
|---|---|
| `request.id` | AIAgent 真实 requestId |
| `client_message.id` | TestApp 写入的 correlationId |
| `eval.environment.active` | 请求创建时是否有有效租约 |
| `eval.environment.revision` | 请求开始时的环境 revision |

查询建议：先用 `client_message.id = correlationId` 查唯一根请求，再结合 `request.id` 与 TestApp 存储的 AgentResponse 对齐。不要创建第二个 `eval.correlationId` 属性。

`gen_ai.output` 保持“单次模型输出”语义，不等于最终 AgentResponse；最终对外文本以 AgentResponse 为准。当前 development Trace 配置为 `FULL_DEBUG`，可能包含对话、工具参数和车辆状态，只能在受控调试环境使用。

---

## 7. GET_VERSION 与运行指纹

`GET_VERSION` 的 `versionFingerprint` 当前包含：

```text
packageName, versionName, versionCode,
stateSchemaVersion, promptHash, sourceRevision,
textModel, traceContentMode
```

- `promptHash` 是对 `assets/prompts/` 相对路径和文件内容做规范化 SHA-256 后的缓存结果，不返回 Prompt 正文。
- `sourceRevision` 当前为 `null`，不能将其当作 Git SHA。
- 当 AIAgent 主 Service 尚未安装 coordinator 时，`textModel` 与 `traceContentMode` 可能不存在；启动主 Service 后再读取即可获得当前运行时值。
- 版本指纹不包含 API Key、authorization header 或 `local.properties` 内容。

---

## 8. TestApp / 电脑端实现检查单

### TestApp Bridge 必须做到

- [ ] Debug variant、包名 `com.hirain.aiagent.test`，显式绑定 EvalDebugService。
- [ ] 严格按本协议传内部 JSON；不要向请求附加未定义字段。
- [ ] 仅内存保存 token；轮转/重连后不信任旧 token。
- [ ] 设置 `clientMessageId = correlationId`，不指定 AIAgent requestId。
- [ ] AgentResponse 回调先按 Eval clientMessageId 截获，避免被普通 UI/TTS 逻辑提前消费。
- [ ] callback 后 READ 返回 `AGENT_BUSY` 时短轮询，不记录中间快照。
- [ ] 生成外层结果前剥离 `leaseToken`、`leaseExpiresAtEpochMs` 和内部 Debug response。
- [ ] 租约期禁用普通聊天及 IMAGE/VOICE/CONTROL 入口。

### 电脑端 Eval 必须做到

- [ ] 以 `AIAgent_Eval/schemas` 为外层唯一标准，不直接解析内部 Debug response。
- [ ] 生成 correlationId，交由 TestApp 映射为 clientMessageId。
- [ ] 外层 `createdAt` / snapshot `capturedAt` 使用 ISO-8601 UTC；嵌套 AgentResponse timestamp 保留 epoch millis。
- [ ] 通过 TestApp 结构化结果读取 AgentResponse、before/after snapshot、revision 和 traceId。
- [ ] 将 `AGENT_BUSY`、协议不兼容、Bridge 错误区分为可重试、配置错误或 case 失败，不能把它们误评为模型答案错误。

---

## 9. 当前验证状态与未关闭项

AIAgent 侧已通过 Debug Eval JVM 测试、全量 `testDebugUnitTest`、`assembleDebug`、`assembleRelease`，并确认 Debug merged manifest 含 EvalDebugService、Release merged manifest 不含该 Service。

尚未关闭的是跨项目工作：TestApp Bridge 的真实 Binder 绑定与结果存储、电脑端 Schema/Fixture 校验、Phoenix 设备证据、低风险 Tool、Safety deny 和二次确认的三方实机闭环。开发方应把这些作为集成验收，不应把本文件视为已完成设备端到端验收。
