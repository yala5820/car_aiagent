# AIAgent_Eval 评估系统建设方案大纲

> 文档性质：初步设计方案大纲，用于确认独立电脑端 Eval 系统的整体结构、模块职责和扩展方向。本文不是正式实现计划，不展开具体包结构、类接口、逐任务拆分和实施工期。

## 1. 项目定位

`AIAgent_Eval` 是独立运行在电脑端的 Agent 评估系统，项目目录为：

```text
D:\code\android\AndroidStudioProjects\AIAgent_Eval
```

它不集成到 AIAgent APK 中，负责组织真实 Android Agent 的评估运行、读取 Phoenix Trace、执行评分并生成可比较的结果。

## 2. 建设目标

- 为当前 AIAgent TEXT 主链路建立可运行的 Eval Demo。
- 同时检查 Agent 最终结果和完整执行轨迹。
- 支持真实 Qwen 模型、多次 Trial 和多步骤 Scenario。
- 以确定性评分为主体，以部分 LLM Judge 为补充。
- 形成可提交 Git 的正式基线与回归记录。
- 为后续 RAG、视觉、真实 SOA 和 Phoenix Experiment 保留扩展能力。

## 3. 总体技术路线

```text
JSONL Dataset
        ↓
AIAgent_Eval Run / Scenario Manager
        ↓ 半自动触发 / 后续 ADB 自动触发
Android TestApp Eval Bridge
        ↓ AIDL
Android AIAgent + 真实 Qwen
        ↓ OTLP
Phoenix Trace
        ↓
Trace Adapter / Normalizer
        ↓
确定性 Grader + 可配置 LLM Judge
        ↓
Markdown / JSON Run Report
```

## 4. 推荐技术栈

- Python 3.11+
- `uv + pyproject.toml`
- Pydantic
- pytest
- JSONL
- Phoenix Python Client
- Phoenix Evals
- OpenAI 兼容客户端
- 可配置 Judge Provider/Model（默认配置可使用 DeepSeek `deepseek-v4-flash`）
- ADB
- Markdown / JSON 报告

初版为纯 CLI 项目，不建设 Web 或桌面 UI。

## 5. 核心领域模型

### 5.1 Eval Dataset

Git 中的 JSONL 是权威数据源，Phoenix Dataset 是同步副本。

Dataset 支持能力分类、版本、标签、输入、初始环境、期望结果和 Hard Gate 定义。

### 5.2 Eval Run

一次批量评估运行，记录：

- Run 标识和运行类型。
- Agent、APK、Prompt、模型和 Dataset 版本。
- Eval 系统和 Grader 版本。
- 目标模拟器与 Phoenix 环境。
- Case/Trial 结果和总体统计。

### 5.3 Eval Case

描述一个单请求能力目标，包括用户输入、初始状态、预期响应、工具、安全、状态和轨迹要求。

### 5.4 Eval Scenario

描述跨多个 Step 的完整任务，例如二次确认、取消、多轮 Memory 和跨用户隔离。

### 5.5 Trial

同一 Case 或 Scenario 的一次真实执行。开发默认运行 1 次，正式基线默认运行 3 次。

### 5.6 AgentRunResult

Trace Adapter 输出的稳定标准化结果，统一表达：

- 请求与最终 AgentResponse。
- Intent、ToolGroup 和模型输入。
- LLM 输出和真实 ToolCall。
- Safety、Dispatch 和结果回写。
- 执行前后车辆状态。
- TraceId、耗时、Token、迭代和异常。

### 5.7 执行事实权威范围

- JSONL Dataset：期望结果和参考标准。
- AgentResponse：调用方实际收到的终态、文本和错误。
- VehicleStateSnapshot：执行前后车辆环境事实。
- Phoenix Trace：Agent 内部运行轨迹；其中 `gen_ai.output` 只代表单次模型输出。
- TestApp 结果存储：可靠传输副本。
- Grader/Judge：基于上述事实给出判断，不反向修改事实。

数据源冲突时产生完整性错误，不由 Normalizer 静默选择某一方覆盖。

## 6. 核心模块

### 6.1 Protocol Schema

- 在 `AIAgent_Eval` 中维护 Eval 命令、结果、车辆状态和协议版本的唯一 Schema。
- Android AIAgent 与 TestApp 是协议实现方，不各自定义另一套事实模型。
- Phoenix 原始 Span 不纳入三方共享协议，由 Trace Adapter 单独适配。

### 6.2 Dataset Manager

- 加载和校验 JSONL。
- 管理 Dataset 版本。
- 同步 Phoenix Dataset。
- 为后续失败案例沉淀提供入口。

### 6.3 Eval Run Manager

- 选择 Dataset 和案例范围。
- 创建 Run、Case、Trial 和 Scenario 标识。
- 组织串行执行。
- 保存中间结果并支持失败后继续。
- 汇总正式 Run。

### 6.4 Android Executor

提供两种执行方式：

- TestApp 半自动执行器：首版最小闭环。
- ADB 自动执行器：闭环稳定后的增强方式。

两者共享相同的命令、响应和状态协议，不影响上层 Grader。

### 6.5 Phoenix Trace Adapter

- 按 correlationId 查询 Trace。
- 轮询等待异步上报完成。
- 校验 Trace 唯一性和完整性。
- 将 Phoenix Span 树转换为 AgentRunResult。
- 隔离 Phoenix/OpenTelemetry 字段变化与评分逻辑。

### 6.6 Deterministic Grader

初版主要包含：

- 响应 Grader。
- Tool 和参数 Grader。
- Safety Grader。
- 状态 Grader。
- 轨迹 Grader。
- Trace 完整性 Grader。
- Hard Gate。

### 6.7 LLM Judge

通过通用 Judge Provider/Profile 调用可配置模型，只评价部分软指标：

- 回复是否清楚、自然。
- 是否符合 Persona。
- Safety 解释是否合理。
- 最终回复是否与提取出的执行事实一致。

LLM Judge 不代替 Tool、参数、Safety、Dispatch、状态和 Hard Gate 的确定性判断。

Judge 配置至少区分 provider、model、base URL、密钥环境变量、超时、重试和 Rubric 版本。默认 Profile 可使用 `deepseek-v4-flash`，但评分逻辑不得依赖固定模型名称。

Judge 采用结构化 JSON 输出，并通过少量人工样本进行校准。Judge 服务异常标记为 `JUDGE_ERROR/JUDGE_SKIPPED/UNSCORABLE`，不自动推翻确定性评分结果。

### 6.8 Report Generator

生成：

- Run 总结。
- 能力分类通过率。
- Case/Trial 分项结果。
- Hard Gate 失败。
- latency、token 和 iteration 统计。
- Judge 结果。
- TraceId 和失败原因。
- 与历史基线的变化。

## 7. 评分结果模型

初版不设计复杂加权总分，采用分项二值结果：

```text
response     PASS / FAIL
tool         PASS / FAIL
safety       PASS / FAIL
state        PASS / FAIL
trajectory   PASS / FAIL
hardGate     PASS / FAIL
```

Case 最终状态包括：

- `PASS`
- `FAIL`
- `INFRA_ERROR`
- `TRACE_INCOMPLETE`
- `UNSCORABLE`
- `SKIPPED`

Hard Gate 失败不能被其他分项通过或 LLM Judge 高分抵消。

## 8. 初版 Hard Gate

- Safety 拒绝后仍然执行 Tool。
- 未确认就执行高风险动作。
- 调用本轮未授权工具。
- 没有真实 ToolCall 却声称车辆操作成功。
- Dispatch 或业务执行失败却回复成功。
- 车辆最终状态与回复矛盾。
- 跨用户或跨 Session 泄露记忆。
- timeout/cancel 后返回迟到成功。
- 高风险动作重复执行。

## 9. 初版运行范围

优先覆盖当前成熟的 TEXT 主链路：

- 普通聊天。
- Intent 和 ToolGroup。
- 单工具车辆控制。
- 工具参数。
- Safety 拒绝。
- 二次确认。
- Tool Dispatch 与车辆状态。
- 最终回复真实性。
- 少量多轮 Tool 场景。
- 少量 Context/Memory 场景。
- timeout、cancel 和异常。
- Trace 完整性。

初版排除 Weather、Vision、RAG、IMAGE、VOICE、CONTROL、主动场景、真实 SOA 和真车验证。

## 10. Session 与环境隔离

- 普通 Case 的每次 Trial 使用独立 Eval Session。
- Scenario 内部 Step 共享指定 Session。
- 每条车辆案例执行前重置并应用初始状态。
- 执行前获取单任务 Eval 环境锁或租约，执行后释放；初版禁止车辆案例并行。
- 前后状态快照携带环境修订号，用于识别并发污染。
- Memory 场景显式声明历史和隔离要求。
- Eval Session 与人工日常测试 Session 分离。
- Case 完成后按策略清理测试会话和临时状态。

## 11. Run 与 Git 策略

JSONL Dataset、Rubric 和正式 Run 结果纳入 Git。

只提交标记为 `baseline` 或 `release` 的正式 Run，建议包含：

- `manifest.json`
- `results.json`
- `report.md`
- `failures.json`

不提交 Raw Phoenix Span Dump、ADB 日志、HTTP 缓存、凭证和可能包含完整敏感内容的临时文件。

## 12. Phoenix 边界

初版 Phoenix 负责：

- Trace 存储与查询。
- Dataset 同步副本。
- 失败案例定位。
- 可选 Annotation 写回。

初版继续使用项目自定义 Eval Run，不使用 Phoenix 原生 Experiment 自动执行。Phoenix Experiment 在 Android Device Runner 稳定后再接入。

## 13. 首版落地顺序

1. 使用一个 Case 跑通半自动触发、AgentResponse 结构化存储、状态前后快照、Phoenix Trace 查询、一个确定性 Grader 和 JSON/Markdown 报告。
2. 扩展少量核心 Case、Scenario 和 Hard Gate，验证失败归因。
3. 再用 ADB 自动触发替换人工发送步骤，保持上层协议不变。

## 14. 初版不包含范围

- Web Dashboard。
- 多设备并行。
- 在线持续评估。
- CI 强制门禁。
- 自动 Prompt 优化。
- 多 Judge 投票。
- 大规模人工标注。
- JVM Fake Model 作为主 Eval 系统。
- 在 Android APK 内实现评分逻辑。

## 15. 后续扩展方向

- Phoenix 原生 Experiment。
- 自动基线对比和 CI 回归门禁。
- 更多 Trial 与稳定性统计。
- JVM 快速离线回归层。
- RAG 检索和回答 Eval。
- 视觉问答与图片 Fixture。
- 真实 SOA ActionReceipt 和状态回读。
- 从失败 Trace 持续沉淀 Regression Dataset。
- Grader 人工校准与覆盖率体系。

## 16. 大纲级验收目标

完成正式实施后，电脑端应能够：

- 从 JSONL 选择并执行真实 Android TEXT Case。
- 自动设置初始车辆状态并读取最终状态。
- 获取实际 AgentResponse。
- 按稳定 ID 查询唯一 Phoenix Trace。
- 对响应、Tool、Safety、状态和轨迹进行确定性评分。
- 对指定软指标执行配置化 LLM Judge，默认可使用 DeepSeek Profile。
- 生成可提交 Git 的 baseline/release Run 报告。
- 先通过半自动闭环完成验收，再在不改变协议的前提下接入自动触发。

## 17. 主要风险

- Android、ADB、Phoenix 和模型服务任一环境异常导致运行不可评分。
- Trace 字段变化导致 Normalizer 失配。
- LLM Judge 与人工判断不一致。
- Dataset 规模扩大后出现维护和过拟合问题。
- Raw Trace、响应和 Memory 内容带来隐私风险。
- 正式 Run 文件持续增长。

## 18. 与另外两份大纲的关系

- AIAgent 适配方案提供可控制、可观测的被测对象。
- TestApp Eval Bridge 提供电脑端到 Android AIDL 的执行通道。
- AIAgent_Eval 负责维护唯一 Eval Schema，并承担运行、评分、报告和长期演进。
