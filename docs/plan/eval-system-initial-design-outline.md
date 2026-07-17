# AIAgent 初版 Eval 系统设计方案大纲

> 文档定位：用于明确 Eval 系统的整体方向和模块边界，不涉及具体接口、类设计、评分公式及实现代码。

## 1. 建设目标

为当前 AIAgent 建立一套轻量级、可运行、可持续扩展的评估体系，用于回答：

- Agent 是否正确理解用户意图。
- Agent 是否选择了正确的工具。
- 工具参数及执行结果是否正确。
- 车辆最终状态是否符合预期。
- Agent 的运行轨迹是否合理。
- Agent 在超时、拒绝、确认等异常场景下是否正确处理。
- 项目改动后是否出现能力退化。

初版重点是“能够稳定执行和发现回归”，暂不追求完整的专业评测平台。

## 2. 总体架构

推荐采用：

> 电脑端 Eval Orchestrator + Android Agent Executor + Trace 运行轨迹 + VehicleStateMachine 最终状态 + Phoenix 可视化

系统主要分为四部分：

1. Eval 数据集  
   定义测试输入、初始环境和预期结果。

2. Eval Runner  
   批量执行测试用例，并收集 Agent 响应、车辆状态和 Trace。

3. Grader 评分器  
   从最终结果和运行过程两个维度进行评分。

4. Eval Report  
   输出 JSON 和 Markdown 报告，展示通过率、失败原因和回归变化。

## 3. 推荐技术栈

初版尽量复用项目现有技术：

- Java 17
- JUnit 4
- Gradle Test
- JSON/JSONL 测试数据
- Gson
- OpenTelemetry Trace
- Phoenix
- Markdown + JSON 评估报告

未来如果需要更复杂的数据分析和 LLM Judge，可以增加 Python、Phoenix Dataset/Experiment 等能力，但不作为初版前置条件。

## 4. 核心模块

### 4.1 Eval Dataset

负责管理标准测试用例。

每条用例原则上包含：

- 用例编号和能力分类
- 用户输入
- 用户、会话和 Persona 信息
- 初始车辆状态
- 预期响应要求
- 预期工具调用
- 预期最终车辆状态
- 是否允许多种合法执行路径

### 4.2 Eval Runner

负责执行评估流程：

- 加载测试用例
- 初始化或重置测试环境
- 调用 Agent
- 等待 Agent 完成
- 收集响应结果
- 收集工具执行记录
- 读取最终车辆状态
- 关联对应 Trace
- 调用 Grader 评分
- 汇总评估报告

### 4.3 Agent Executor

用于隔离不同执行环境，初版可支持两种实现：

- 离线执行器：在电脑端运行确定性测试，使用 Fake 或 Scripted Model。
- Android 执行器：通过模拟器或真实设备执行真实 Agent 和模型请求。

两种模式使用相同的用例和评分定义，避免形成两套 Eval 系统。

### 4.4 Trace Adapter

现有 Trace 作为 Agent 运行过程的主要事实来源，负责提供：

- Agent 循环次数
- Context 装配过程
- 模型调用
- Tool Calling
- 安全审核
- 工具执行与结果回写
- Memory 压缩与提取
- 响应分发
- 错误、超时和耗时信息

Trace 主要用于轨迹评分和失败定位，但不能代替预期答案及车辆最终状态判断。

### 4.5 Grader

初版建议包含四类评分器：

- 响应评分：检查成功状态、错误类型和必要内容。
- 工具评分：检查工具名称、参数和执行结果。
- 状态评分：比较 VehicleStateMachine 执行前后的状态。
- 轨迹评分：通过 Trace 检查执行路径、安全审核和循环行为。

初版以确定性规则评分为主，不立即引入 LLM Judge。

### 4.6 Eval Report

报告至少展示：

- 总用例数和通过率
- 各能力分类通过率
- 各用例评分结果
- 响应、工具、状态和轨迹的分项结果
- 失败原因
- 对应 Trace ID
- 执行耗时
- 与上一版本相比的回归情况

## 5. 两种运行模式

### 5.1 离线确定性评估

运行位置：电脑端 Gradle/JUnit 环境。

适合评估：

- Runtime 流程
- Context 装配
- ToolGroup 选择
- AgentLoop 状态转换
- Tool 调度
- Safety 规则
- 车辆状态变化
- 超时和异常处理

优点是快速、稳定、成本低，适合作为日常回归测试。

### 5.2 在线真实 Agent 评估

运行位置：电脑端负责调度，Android 模拟器或设备负责执行。

适合评估：

- 真实模型对话
- 真实 Tool Calling
- 多轮决策能力
- Prompt 效果
- 非确定性输出
- 真实 Trace 完整性
- 端到端响应时间

在线评估可以对同一用例执行多次，统计稳定通过率，而不是只执行一次。

## 6. 初版评估范围

建议初版优先覆盖：

1. 普通对话
2. 单工具车辆控制
3. 工具参数提取
4. 多轮工具调用
5. 高风险操作拒绝
6. 高风险操作二次确认
7. 不支持的请求
8. 模型或工具执行失败
9. 请求超时与取消
10. Context 和 Memory 基础行为
11. Trace 完整性
12. 车辆最终状态正确性

RAG 和前向视野问答可以在相关功能实现后，以新的数据集分类接入，不需要重建 Eval 架构。

## 7. 结果判断原则

Eval 不应只根据 Agent 最终回复判断成功，而应分别检查：

1. 预期结果：数据集定义的目标。
2. 环境结果：车辆最终状态是否正确。
3. 业务结果：AgentResponse 是否正确。
4. 执行轨迹：Agent 是否通过合理、安全的路径完成任务。

例如，Agent 回复“已打开空调”，但车辆状态没有变化，应判定任务失败；Trace 则用于进一步定位是模型未调用工具、工具被安全审核拒绝，还是工具执行失败。

## 8. 与现有 Trace 的关系

Eval 和 Trace 的职责需要分开：

- Trace 回答：Agent 实际做了什么。
- Eval 回答：Agent 做得是否正确。

建议未来在 Trace 中补充少量 Eval 关联字段：

- Eval Run ID
- Eval Case ID
- Dataset Version
- Trial Number
- Agent Variant

这样能够从评估报告直接定位到 Phoenix 中对应的完整运行轨迹。

## 9. 推荐目录边界

初步建议：

```text
AIAgent/
├── app/src/test/.../eval/       # 离线确定性评估
├── eval/
│   ├── datasets/                # 测试数据集
│   ├── runner/                  # 电脑端评估调度
│   ├── graders/                 # 评分规则
│   ├── adapters/                # Android、Trace 等适配
│   └── reports/                 # 生成的评估报告
└── docs/
    └── eval/                    # Eval 设计和使用文档
```

完整 Eval Runner 不建议放入正式 APK。Android 端如需提供状态重置、图片注入或结果读取能力，应优先放在 Debug/Test 范围内。

## 10. 分阶段建设建议

### 第一阶段：最小闭环

- 定义 EvalCase
- 建立少量 JSON 用例
- 实现离线 Eval Runner
- 完成响应、工具和车辆状态评分
- 生成 Markdown/JSON 报告

### 第二阶段：接入真实 Android Agent

- 增加 Android 执行适配
- 关联 Trace ID
- 接入 Phoenix 轨迹查看
- 支持同一用例多次运行

### 第三阶段：能力扩展

- 增加 RAG 评估集
- 增加视觉问答评估集
- 增加版本对比和回归门禁
- 引入 LLM Judge 和人工标注
- 接入 Phoenix Dataset/Experiment

## 11. 初版明确不做的内容

为控制复杂度，初版暂不建设：

- 独立数据库
- Web 管理后台
- 复杂测试 DSL
- 全量 LLM Judge
- 自动生成测试用例
- 完整 CI 质量门禁
- 生产 APK 内置 Eval 平台
- 大规模人工标注平台

## 12. 方案结论

初版推荐采用“轻量 Eval Core”：

- JUnit/Java 负责确定性回归。
- 电脑端 Runner 负责批量评估。
- Android 设备负责真实 Agent 执行。
- VehicleStateMachine 判断任务最终结果。
- 现有 Trace 提供完整执行轨迹。
- Phoenix 用于观察和定位失败。
- JSON/Markdown 用于沉淀可比较的评估结果。

这个方案能够较快形成 Eval 闭环，同时为后续 RAG、视觉问答、LLM Judge 和 Phoenix Experiment 保留扩展空间。
