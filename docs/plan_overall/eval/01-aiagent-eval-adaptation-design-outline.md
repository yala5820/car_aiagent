# AIAgent Eval 适配整改设计方案大纲

> 文档性质：初步设计方案大纲，用于确认 AIAgent 作为被评估对象需要补充的测试适配能力。本文不是正式实现计划，不展开类、方法、逐文件改动和任务工期。

## 1. 模块定位

AIAgent 继续作为 Android 端真实 Agent 运行主体，保持现有 Runtime、Context、AgentLoop、Tool、Safety、Memory 和 Trace 业务语义不变。

本次适配的目标不是在 AIAgent 内建设 Eval 系统，而是让外部电脑端 `AIAgent_Eval` 能够稳定地：

- 关联一次 Eval Case 与真实 Agent Trace。
- 设置并读取虚拟车辆测试环境。
- 获取可以验证的最终执行事实。
- 识别当前 APK、Prompt、模型和 Trace 配置版本。
- 覆盖二次确认、取消和异常等跨步骤路径。

## 2. 设计目标

### 2.1 可关联

电脑端使用一个跨系统 `correlationId` 关联 Eval Case、Android 请求、最终响应和 Phoenix Trace；Run、Case、Trial、Scenario、Step 等编排标识保留在电脑端管理。

### 2.2 可控制

Eval 执行前能够重置并设置 `VehicleStateMachine` 初始状态，避免不同案例相互污染。

### 2.3 可观测

Trace 能够表达 Agent 的真实运行轨迹，最终 AgentResponse 和车辆状态能够通过受控通道读取。

### 2.4 不侵入业务

所有 Eval 专用能力限定在 Debug/Test 环境，不改变正式 Agent 的业务决策、安全规则和请求终态。

## 3. 总体设计原则

- Eval 核心不进入 AIAgent APK。
- AIAgent 只提供事实和测试控制接口，不负责评分。
- Eval 元数据不得污染模型可见 Prompt。
- Debug 状态接口不得绕过 Safety 直接代替正常 Tool 执行。
- 状态设置只发生在案例执行前，正式执行仍走真实 Agent 主链路。
- Release 构建默认不暴露 Eval Debug 能力。
- 新增 Trace 字段保持结构化、低歧义和可版本化。
- Eval 共享协议以电脑端 `AIAgent_Eval` 中的 Schema 为唯一标准，Android 两端只负责实现协议。
- 首版验收以最小半自动闭环为准，自动触发能力在闭环稳定后补充。

## 4. 当前需要补齐的能力

### 4.1 Eval 关联标识

ID 按职责收敛：

- 电脑端管理 `runId/caseId/trialId/scenarioId/stepId`，不要求全部进入 Android 请求。
- 电脑端只向 Android 提供一个跨系统 `correlationId`。
- TestApp 将 `correlationId` 写入现有 `clientMessageId`，两者语义等同。
- TestApp 继续生成 `requestId`，用于兼容现有运行中请求取消机制；电脑端不指定该 ID。
- AIAgent 负责在 AgentResponse 和 Trace 中回显 `requestId`、`clientMessageId/correlationId`。
- Trace 的 `traceId` 由 OpenTelemetry 管理，二次确认的 `confirmationId` 由 AIAgent 管理。

初版不为每层编排对象重复增加跨系统 ID，也不为收敛 ID 改造现有 AIDL 请求准入与取消契约。

### 4.2 最终响应事实

保留两类输出：

- 最后一次模型输出，用于判断 LLM 本身的行为。
- 最终 AgentResponse，用于判断真实返回给调用方的系统结果。

最终响应主要由 TestApp 回传给电脑端；AIAgent Trace 可补充必要的终态、长度、错误和受控内容信息，但不把 Trace 当作唯一响应传输通道。

### 4.3 VehicleStateMachine Debug 控制

为当前 8 个车辆子系统建立统一、结构化、带版本号的状态快照能力：

- AC
- Door
- Window
- Seat
- Speed
- Chassis
- Fragrance
- DMS

初版至少支持：

- 获取和释放单个 Eval 环境使用权，首版仅允许串行执行。
- 重置到默认状态。
- 应用 Eval Case 指定的初始状态。
- 读取完整状态快照。
- 校验状态快照版本、环境修订号和设置结果。

首版采用轻量的 Eval 锁或租约隔离普通请求与评估请求，不创建多套 `VehicleStateMachine`；异常退出时通过超时或强制清理释放环境。

### 4.4 二次确认执行证据

二次确认是跨两个 TEXT 请求的 Scenario。需要确保：

- 第一条 Trace 记录待确认动作与确认标识。
- 第二条 Trace 记录确认结果并复用同一确认标识。
- 确认后的真实 Tool 派发具有可验证的工具、参数、结果和执行状态。
- 最终车辆状态与确认结果能够相互印证。

### 4.5 版本指纹

为 Eval Run 提供可比较的被测版本信息，至少覆盖：

- APK/应用版本。
- 源码或构建标识。
- Prompt 资源版本或摘要。
- 主模型名称和关键配置。
- Trace 内容采集模式。
- 状态快照 schema 版本。

### 4.6 执行事实权威边界

不同数据源各自负责一种事实，发生冲突时标记完整性错误，不静默覆盖：

| 数据源 | 权威范围 |
|---|---|
| AgentResponse | 调用方实际收到的终态、文本、错误和请求标识 |
| VehicleStateSnapshot | 执行前后车辆环境事实及环境修订号 |
| Phoenix Trace | 模型、Context、Tool、Safety、Dispatch、Memory、耗时等运行轨迹 |
| `gen_ai.output` | 单次模型调用输出，不等同于最终 AgentResponse |
| TestApp 结果存储 | 上述事实的可靠传输副本，不创造业务事实 |

## 5. Debug 接口边界

建议提供独立的 Debug Eval 能力入口，由 TestApp 访问，电脑端不直接操作 Android Binder。

该入口只承担：

- Eval 环境使用权的获取、校验和释放。
- 状态重置、设置和读取。
- 版本信息读取。
- 必要的测试环境清理。
- Eval 关联元数据接收与回传。

明确禁止：

- 直接替代 Agent 调用 Tool。
- 绕过 ToolSafetyEngine。
- 修改正式 Runtime 路由结果。
- 在 Release 环境开放无保护的车辆状态写入。

## 6. 与现有 Trace 的关系

现有 Trace 继续作为 Agent Transcript 和执行轨迹事实来源，主要提供：

- 请求准入与终态。
- Intent 和 ToolGroup。
- Context 装配。
- 模型调用与 ToolCall。
- Safety、Dispatch 和结果回写。
- Memory 行为。
- 耗时、Token、迭代和异常。

本次只补齐 Eval 关联、确认执行、版本指纹等必要信息，不重建 Trace 系统。

## 7. 概念运行流程

```text
AIAgent_Eval 生成 Case/Trial/Step 标识
        ↓
TestApp 获取 Eval 环境使用权并设置车辆初始状态
        ↓
AIAgent 返回初始状态快照
        ↓
TestApp 发送真实 AgentRequest
        ↓
AIAgent 正常运行并上报 Phoenix Trace
        ↓
TestApp 接收真实 AgentResponse
        ↓
TestApp 读取最终车辆状态
        ↓
TestApp 写入 Debug 专用结构化结果
        ↓
电脑端读取结果并汇总响应、状态与 Trace
```

## 8. 初版包含范围

- TEXT 主链路 Eval 关联。
- Debug-only 状态机控制与读取。
- 串行 Eval 环境隔离。
- 最终 AgentResponse 的稳定标识。
- 二次确认跨 Trace 证据。
- 版本指纹。
- 必要的测试隔离和错误返回。

## 9. 初版不包含范围

- 在 AIAgent 内实现 Dataset、Grader 或 Report。
- 修改模型决策、ToolGroup 或 Safety 业务规则。
- IMAGE、VOICE、CONTROL 和主动场景 Eval 适配。
- RAG、视觉和真实 SOA 评估能力。
- Release 环境远程状态控制。
- Phoenix 原生 Experiment 执行逻辑。

## 10. 大纲级验收目标

完成正式实施后，AIAgent 应满足：

- 外部 Eval 可以通过稳定 ID 唯一找到对应 Trace。
- 每条 Case 可以从确定的车辆初始状态开始。
- Eval 执行期间普通请求不会并发污染车辆状态。
- Eval 可以读取实际 AgentResponse 和最终车辆状态。
- 二次确认可以由两个请求和两个 Trace 组成完整证据链。
- Debug 适配不改变正常用户请求的业务行为。
- Release 构建不暴露未授权的 Eval 控制能力。

## 11. 主要风险

- Eval 元数据意外进入模型 Prompt。
- Eval 环境锁未释放或环境修订号不一致。
- 状态快照 schema 随业务类变化而漂移。
- Debug 接口错误进入 Release 构建。
- 为 Eval 增加的 Trace 内容带来隐私或体积压力。

## 12. 与另外两份大纲的关系

- TestApp Eval Bridge 负责调用本大纲定义的 Debug 能力并回传结果。
- AIAgent_Eval 负责根据本模块提供的事实执行评分。
- 三个模块共享同一套 Eval 关联协议和状态快照协议，其唯一 Schema 定义位于电脑端 `AIAgent_Eval` 项目。
