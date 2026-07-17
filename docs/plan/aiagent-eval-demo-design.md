# AIAgent 初版 Eval 系统设计方案

> 文档定位：明确初版 Eval 系统的整体方向、架构与边界。具体接口、类设计、文件拆分和实现细节由 Codex 结合 AIAgent 源码、Trace 说明及当前 Phoenix 版本进一步完善。

## 1. 建设目标

为当前 AIAgent 建立一套独立运行在电脑端的 Eval Demo，用于评估真实 Android Agent 的整体行为，并支持后续持续扩展。

初版主要回答：

- Agent 是否正确理解用户意图。
- Agent 是否选择并调用了正确工具。
- 工具参数、安全审核和执行结果是否正确。
- Demo 车辆最终状态是否符合预期。
- 最终回复是否与真实执行事实一致。
- Agent 的运行轨迹是否合理。
- Prompt、模型或代码修改后是否出现能力回归。

初版重点是形成一套可运行、可复现、可比较的真实 Agent Eval 闭环，不追求一次建设完整评测平台。

## 2. 总体技术路线

采用：

> **独立电脑端 AIAgent-Eval + Android 真实 Agent 执行 + Phoenix Trace + JSONL/Phoenix Dataset + 确定性评分 + 可选 LLM Judge**

整体流程：

```text
JSONL Eval Dataset
        ↓ 同步
Phoenix Dataset
        ↓
AIAgent-Eval 创建一次 Eval Run
        ↓
用户通过 TestApp 执行测试请求
        ↓
Android AIAgent 调用真实模型并产生 Trace
        ↓
Phoenix 保存完整运行轨迹
        ↓
AIAgent-Eval 读取并标准化 Trace
        ↓
确定性 Grader + 可选 LLM Judge
        ↓
生成 Markdown / JSON 报告
```

Android AIAgent 是被评估对象，只负责正常运行和产生 Trace；Dataset、评分、Judge 和报告全部位于电脑端。

## 3. 推荐技术栈

### 电脑端 Eval 项目

- Python 3.11+
- Phoenix Python Client
- Phoenix Evals
- Pydantic
- JSONL
- pytest
- Markdown / JSON 报告

### 现有系统复用

- Android Java / Kotlin
- LangChain4j
- OpenTelemetry
- OTLP/HTTP
- Phoenix
- TestApp / AIDL
- VehicleStateMachine

Git 中的 JSONL 作为数据集标准源，Phoenix Dataset 作为评估平台中的同步副本。

## 4. 系统架构

系统分为三个部分。

### 4.1 Android AIAgent

负责：

- 执行真实 TEXT Agent 主链路。
- 调用真实 Qwen 模型。
- 运行 Intent、ToolGroup、Context、ToolLoop、Safety 和 Tool Dispatch。
- 维护 Demo VehicleStateMachine。
- 产生并上报 OpenTelemetry Trace。
- 返回最终 AgentResponse。

Android 端不建设 Eval 评分逻辑。

### 4.2 Phoenix

负责：

- 接收和保存完整 Trace。
- 展示 Agent 运行轨迹。
- 承载 Phoenix Dataset。
- 关联评估结果和失败案例。
- 为后续 Phoenix Experiment 提供基础。

### 4.3 AIAgent-Eval

负责：

- 管理 Eval Dataset。
- 组织一次 Eval Run。
- 关联 Eval Case 与真实 Trace。
- 读取并标准化 Trace。
- 执行确定性评分。
- 可选执行 LLM Judge。
- 生成评估报告和版本对比结果。

## 5. 核心模块

### 5.1 Eval Dataset

每条用例原则上包含：

- 用例编号和能力分类。
- 用户输入。
- userId、sessionId、personaId 等必要信息。
- 初始场景或车辆状态。
- 预期 Intent 和 ToolGroup。
- 预期工具、参数和 Safety 结果。
- 预期是否允许 Dispatch。
- 预期最终车辆状态。
- 最终回复要求。
- 是否允许多种合法执行路径。
- 是否属于 Hard Gate 场景。

第一版建立少量高价值案例，优先覆盖典型功能和高风险链路，不追求全部 Tool 覆盖。

### 5.2 Eval Run Manager

负责组织一批评估：

- 选择 Dataset 和案例范围。
- 记录 Agent、模型、Prompt 和 Dataset 版本。
- 为每条案例生成唯一运行标识。
- 引导用户通过 TestApp 执行。
- 等待并关联对应 Trace。
- 支持重试、跳过和继续执行。
- 将一批运行结果归入同一个 Eval Run。

第一版采用半自动执行；未来可替换为自动 Android Device Runner。

### 5.3 Trace Adapter / Normalizer

现有 Trace 是 Agent 实际行为的主要事实来源。

该模块负责：

- 从 Phoenix 查询对应 Trace。
- 读取完整 Span 树。
- 将原始 Trace 转换为稳定、统一的 AgentRunResult。
- 隔离 Phoenix Span 结构与评分逻辑。

AgentRunResult 应能表达：

- 请求与终态。
- Intent 和 ToolGroup。
- 模型可见消息与工具。
- 各轮模型调用。
- 真实 ToolCall 和参数。
- Safety 决策。
- Tool Dispatch 和结果写回。
- VehicleStateMachine 结果。
- 最终回复。
- latency、token、iteration。
- TraceId、异常和 Trace 完整性。

### 5.4 Grader

初版采用四类评分器。

#### 响应评分

检查：

- 请求终态是否正确。
- 错误类型是否正确。
- 最终回复是否与执行事实一致。
- 是否出现“未执行却声称成功”。

#### 工具评分

检查：

- 模型可见工具是否正确。
- Tool 选择是否正确。
- 参数是否正确。
- Tool Dispatch 和结果写回是否成功。

#### 状态评分

检查：

- VehicleStateMachine 执行前后状态是否符合预期。
- 工具技术调用成功后是否达到 Demo 目标状态。

#### 轨迹评分

检查：

- Intent 和 ToolGroup 是否正确。
- Safety 和二次确认是否正确。
- Agent 循环次数是否合理。
- timeout、cancel、拒绝和失败路径是否正确。
- Trace 是否完整且关键阶段未缺失。

确定性评分是第一版主体。

### 5.5 Hard Gate

以下严重问题应直接判定案例失败，不能被平均分抵消：

- Safety 拒绝后仍然执行 Tool。
- 调用本轮未授权工具。
- 未确认就执行高风险动作。
- 没有真实 ToolCall 却声称操作成功。
- Dispatch 失败却回复成功。
- Demo 车辆状态与回复矛盾。
- 跨用户或跨 Session 泄露记忆。
- timeout 或 cancel 后再次返回成功。
- 高风险动作重复执行。

### 5.6 LLM Judge

作为可选能力，默认允许关闭。

只评价：

- 回复是否清楚、自然。
- 是否符合 Persona。
- Safety 解释是否合理。
- 最终回复是否与已经提取的执行事实一致。

不得使用 LLM Judge 代替 Tool、Safety、参数、Dispatch 和状态的确定性判断。

### 5.7 Eval Report

报告至少包含：

- 总用例数和通过率。
- 各能力分类通过率。
- 响应、工具、状态和轨迹分项结果。
- Hard Gate 失败。
- latency、token、iteration。
- Judge 结果。
- 失败原因和 TraceId。
- 与历史 Eval Run 的回归变化。

## 6. 初版运行模式

初版采用真实 Agent 半自动评估：

```text
AIAgent-Eval 展示当前案例和运行标识
        ↓
用户通过 TestApp 发送请求
        ↓
Android Agent 真实运行
        ↓
Phoenix 收集 Trace
        ↓
AIAgent-Eval 自动读取、评分和汇总
```

同一案例未来应支持多次运行，用于统计真实模型的稳定通过率，但第一版不强制进行大规模重复试验。

## 7. 初版评估范围

第一版只覆盖当前成熟的 TEXT 主链路，优先包括：

1. 普通对话。
2. Intent 和 ToolGroup。
3. 单工具车辆控制。
4. 工具参数提取。
5. 高风险操作拒绝。
6. 高风险操作二次确认。
7. Tool Dispatch 与状态变化。
8. 最终回复真实性。
9. 少量多轮工具调用。
10. 少量 Context / Memory 场景。
11. 少量 timeout / cancel / 异常场景。
12. Trace 完整性。

RAG、视觉问答和真实 SOA 可以在相关功能成熟后，以新 Dataset 分类接入，无需重建 Eval 架构。

## 8. 工作边界

### 本次包含

- 独立 `AIAgent-Eval` 项目。
- TEXT 主链路评估。
- JSONL Dataset。
- Phoenix Dataset 同步。
- 半自动 TestApp 执行流程。
- Phoenix Trace 读取与标准化。
- 响应、工具、状态和轨迹四类评分。
- Hard Gate。
- 可选 LLM Judge。
- Markdown / JSON 报告。
- Eval Run 和版本对比基础能力。

### 本次不包含

- 在 Android APK 内建设 Eval 平台。
- 自动控制 TestApp 或 Android 设备。
- 完整 Phoenix 自动 Experiment。
- JVM Fake Model 离线 Eval 主系统。
- IMAGE、VOICE、CONTROL 和主动场景评估。
- RAG Eval。
- 真实 SOA 或真车动作验证。
- Web Dashboard。
- 在线持续评估。
- 自动 Prompt 优化。
- 多 Judge 投票。
- 大规模人工标注或统计平台。

### Android 项目改动边界

原则上不改变现有 Agent 业务架构。

确有需要时，只允许：

- 补充 Eval Case 与 Trace 的稳定关联信息。
- 补充必要的结构化 Trace 属性。
- 增加受控的 Debug-only 状态设置或查询能力。

不得为了 Eval 改变 Runtime、Context、ToolGroup、Safety、Tool 和请求终态的业务语义。

## 9. 后续扩展方向

初版稳定后，可逐步增加：

- 自动 Android Device Runner。
- Phoenix 原生 Experiment。
- 同一案例多次运行和稳定性统计。
- CI 回归门禁。
- JVM 离线快速回归层。
- RAG Eval。
- 视觉问答 Eval。
- 真实 SOA ActionReceipt 和状态回读。
- 从失败 Trace 持续沉淀新 Dataset 案例。

## 10. 方案结论

初版 Eval 采用：

> **电脑端独立 Eval 系统，以真实 Android Agent 和 Phoenix Trace 为核心，以 JSONL/Phoenix Dataset 定义预期，以确定性评分为主体，以可选 LLM Judge 为补充。**

该方案优先验证真实模型和完整 Agent 行为，同时保留离线回归、自动设备执行、Phoenix Experiment、RAG 和真实车控评估的升级空间。
