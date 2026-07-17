# AIAgentTestApp Eval Bridge 整改设计方案大纲

> 文档性质：初步设计方案大纲，用于确认 TestApp 在电脑端 Eval 与 Android AIAgent 之间承担的桥接职责。本文不是正式实现计划，不展开具体页面、类、方法和逐文件任务。

## 1. 模块定位

AIAgentTestApp 作为 Android 侧 Eval Bridge，连接电脑端 `AIAgent_Eval` 与 AIAgent AIDL。

它不是 Eval 评分系统，也不是新的 Agent Runtime。其核心职责是：

- 接收电脑端发送的 Eval 执行命令。
- 使用指定标识构造真实 AgentRequest。
- 调用 AIAgent Debug 状态接口。
- 接收实际 AgentResponse。
- 将结构化执行结果返回电脑端。
- 首版支持半自动最小闭环，并为后续 ADB 自动触发保留入口。

## 2. 设计目标

### 2.1 最小半自动闭环优先

首版允许用户在 TestApp 中确认并发送电脑端准备好的 Eval Case，响应、状态和 Trace 仍按统一协议采集，以最小工作量先验证完整评估链路。

### 2.2 自动执行后续接入

整体架构保留 ADB 自动命令入口；半自动闭环稳定后，只替换请求触发步骤，不改变结果协议、评分和报告逻辑。

### 2.3 不承担评分

TestApp 只返回原始事实，不判断 PASS/FAIL，不实现 Grader 或 LLM Judge。

### 2.4 Debug 环境隔离

Eval Bridge 只在 Debug/Test 环境启用，不改变普通聊天界面的默认使用方式。

## 3. 总体通信结构

```text
AIAgent_Eval CLI
        ↓ ADB 显式命令
TestApp Eval Bridge
        ↓ AIDL
AIAgent
        ↓ AgentResponse Callback
TestApp Eval Bridge
        ↓ Debug 专用结构化结果存储 + ADB 读取
AIAgent_Eval CLI
```

Phoenix Trace 不经过 TestApp 转发，由 AIAgent 直接上报 Phoenix，电脑端 Eval 直接查询。

## 4. Eval 命令模型

命令遵循 `AIAgent_Eval` 项目维护的版本化 Schema，主要包含：

- Eval Run、Case、Trial、Scenario 和 Step 标识。
- 跨系统唯一 `correlationId`。
- userId、sessionId、personaId。
- 请求类型和用户输入。
- 当前 Step 动作类型。
- 初始状态或状态控制参数。
- 超时与执行模式。

初版 Step 动作可覆盖：

- 发送 TEXT 请求。
- 重置车辆状态。
- 设置车辆状态。
- 读取车辆状态。
- 创建、切换或删除 Eval Session。
- 取消指定 Agent 请求。
- 等待或结束 Scenario。

## 5. 执行与结果通道设计

### 5.1 ADB 命令入口

电脑端使用显式 ADB 命令将结构化参数发送给 TestApp Debug 入口。

设计要求：

- 命令必须携带唯一 `correlationId`。
- 不按用户输入文本猜测所属 Case。
- 只接受 Debug 构建允许的显式调用。
- 命令解析失败时返回结构化错误。

ADB 自动触发属于半自动闭环稳定后的增强项，不作为首版最小闭环的前置验收条件。

### 5.2 AIDL 请求构造

电脑端不指定 AIAgent 的 `requestId`。TestApp 将电脑端提供的 `correlationId` 写入现有 `clientMessageId`，并继续为每个真实请求生成 `requestId`，从而保持运行中取消能力。

普通人工聊天仍保持原有随机 ID 行为，Eval 模式与普通模式互不干扰。

### 5.3 AgentResponse 回传

TestApp 需要将真实 AIDL Callback 中的 AgentResponse 序列化为稳定结果，至少包含：

- 关联标识。
- requestId、clientMessageId 和 sessionId。
- success、status 和 text。
- errorType 和 errorDetail。
- 响应时间和 TestApp 接收时间。

TestApp 将每次执行结果写入 Debug 专用结构化结果存储，由电脑端通过 ADB 按 `correlationId` 读取。写入采用临时文件后原子替换，并区分 `PENDING/RUNNING/TERMINAL` 状态；Logcat 只承担诊断，不作为正式结果通道。

具体采用应用内部目录配合 `run-as`，还是 ADB 可访问的应用专用目录，在正式计划中通过模拟器验证后确定。

## 6. 状态机桥接

TestApp 负责调用 AIAgent Debug 状态接口，不在自身保存车辆真值。

状态操作包括：

- 获取和释放 Eval 环境使用权。
- 重置为默认状态。
- 应用完整或局部初始状态。
- 读取执行前快照。
- 读取执行后快照。
- 将状态接口错误原样返回电脑端。

TestApp 不应直接模拟 Tool 成功，也不应绕过 AIAgent 修改业务结果。

初版只支持单 Eval 任务串行执行；结果中携带环境修订号，环境所有权不匹配时立即返回结构化冲突错误。

## 7. Scenario 执行支持

TestApp 按电脑端指令逐 Step 执行，不自行决定下一步业务动作。

典型 Scenario 包括：

- 高风险动作请求 → 确认执行。
- 长请求 → 取消请求。
- 多轮对话 → 验证 Session Memory。
- 用户 A 写入信息 → 用户 B 读取验证隔离。

同一 Scenario 的 Step 共享 scenarioId 和必要的 user/session 信息。每个请求拥有独立的 TestApp `requestId` 和 Trace，并使用当前 Step 的 `correlationId/clientMessageId` 关联电脑端记录。

## 8. 超时与终态

TestApp Bridge 应区分：

- ADB 命令接收失败。
- AIAgent Service 未连接。
- AIDL 调用失败。
- 等待 AgentResponse 超时。
- AIAgent 返回 TIMEOUT/CANCELLED/FAILED。
- TestApp 自身 Watchdog 超时。

TestApp 自身超时不能替代 AIAgent 的真实请求终态，电脑端最终还需结合 AgentResponse 和 Phoenix Trace 判断。

## 9. 半自动首版模式

首版最小闭环流程为：

```text
AIAgent_Eval 显示当前 Case 与关联 ID
        ↓
用户在 TestApp Eval 模式确认并发送
        ↓
TestApp 仍使用指定 ID 构造请求
        ↓
响应、状态和 Trace 继续自动采集
```

后续自动化只替换“用户确认并触发请求”这一步，不改变 Dataset、关联、评分和报告协议。

## 10. 安全与构建边界

- Eval Receiver/Service 只在 Debug/Test 构建注册。
- 优先使用显式 Component 和受控调用。
- 不在日志中输出 API Key 或其他凭证。
- 普通 UI 不自动进入 Eval 模式。
- Release 构建不响应电脑端状态写入命令。
- TestApp 不负责持有 DeepSeek 或 Phoenix 凭证。

## 11. 初版包含范围

- 单模拟器、单设备串行执行。
- 半自动最小闭环。
- Debug 结构化结果存储与 ADB 读取。
- 为 ADB 自动触发预留统一命令入口。
- TEXT 请求和多步骤 Scenario。
- AgentResponse 结构化回传。
- Debug 状态机调用。
- Session 创建、切换、删除和取消请求桥接。

## 12. 初版不包含范围

- 多设备并行调度。
- UI 自动点击和图像识别式自动化。
- 在 TestApp 内执行 Grader。
- Phoenix Trace 查询。
- LLM Judge。
- IMAGE、VOICE、视觉和真实 SOA Eval Bridge。
- 面向生产用户的远程控制能力。

## 13. 首版大纲级验收目标

完成正式实施后，TestApp 应满足：

- 电脑端可以准备指定 TEXT Eval Step，并由 TestApp 半自动确认触发。
- 后续可用 ADB 自动触发替换人工确认步骤，而不改变数据协议。
- 请求使用电脑端提供的 `correlationId`，但 `requestId` 仍由 TestApp 生成。
- 实际 AgentResponse 能按关联 ID返回电脑端。
- 状态设置、前快照和后快照可被电脑端获取。
- 二次确认和取消 Scenario 可以连续执行。
- Debug 结构化结果能够被 ADB 稳定读取，Logcat 仅用于诊断。

## 14. 主要风险

- Android 后台启动或广播限制导致命令未执行。
- TestApp Watchdog 与 AIAgent 真实 deadline 不一致。
- 结构化结果文件残留、重复写入或清理策略不当。
- 普通聊天请求与 Eval 请求共用状态造成干扰。
- Debug 入口配置错误进入 Release 构建。

## 15. 与另外两份大纲的关系

- AIAgent 适配方案提供状态、Trace 和版本事实。
- TestApp 负责把电脑端命令转换成 AIDL 请求。
- AIAgent_Eval 负责组织 Scenario、查询 Phoenix、评分和生成报告。
- Eval 命令与结果 Schema 以 AIAgent_Eval 项目为唯一标准，TestApp 是协议实现方。
