# AIAgent_Eval 电脑端评估系统详细实现计划

> **文档性质：** 可直接交给子 Agent 在 Goal 模式中逐 Phase 执行的详细工作计划。
> **目标项目：** `D:\code\android\AndroidStudioProjects\AIAgent_Eval`
> **计划存放位置：** `AIAgent\docs\plan_overall\eval`
> **设计依据：** `03-aiagent-eval-system-design-outline.md`，并受 `01`、`02` 两份 Android 侧大纲的协议边界约束。
> **当前基线：** 目标项目仅有 `AGENTS.md`、`CLAUDE.md` 和空 `docs/`，尚无 Python 工程、依赖、源码或测试。
> **执行方式：** 子 Agent 必须按复选框逐项推进；每个 Phase 独立完成实现、测试和结果汇报后，才能进入下一 Phase。

## 0. Goal 窗口交接与机器前置检查

### 0.1 计划交接

本计划的权威文件是：

```text
D:\code\android\AndroidStudioProjects\AIAgent\docs\plan_overall\eval\04-aiagent-eval-pc-implementation-plan.md
```

新 Goal 窗口的工作目录是：

```text
D:\code\android\AndroidStudioProjects\AIAgent_Eval
```

启动 Goal 前必须保证新窗口能够读取本计划全文。若新窗口的文件权限不能读取兄弟仓库，用户需要先把本计划复制到 `AIAgent_Eval\docs\`，再让执行者以该副本为执行清单；不得让执行者根据聊天摘要重新猜测计划。执行副本只用于勾选进度，协议和范围变化仍需回写权威计划或经用户明确确认。

一次 Goal 只执行一个 Phase。首次 Goal 的目标应明确写为“完成本计划 Phase 1 并通过 Phase 1 完成门”，不能把四个 Phase 合并成一个无检查点的长 Goal。

### 0.2 2026-07-16 本机快照

本计划最终审计时确认：

- `AIAgent_Eval` 尚不是 Git 仓库。
- 当前可用 Python 为 3.13.3，满足 Python 3.11+ 要求；执行时仍需重新检查。
- `uv` 当前不在 PATH。
- `adb` 当前不在 PATH，但 Android SDK 中存在 `D:\code\android\forSdk\Sdk\platform-tools\adb.exe`。
- 目标项目仍只有 `AGENTS.md`、`CLAUDE.md` 和空 `docs/`。

这些是交付时快照，不是永久配置。Phase 1 开始前重新检查；若 `uv` 仍缺失，执行者必须先向用户申请安装授权，不能静默修改全局 Python 或系统 PATH。ADB 不要求全局加入 PATH，优先通过 `android.adbPath` 使用已确认的绝对路径。

## 1. 工作目标

在独立电脑端项目 `AIAgent_Eval` 中建立一个可运行的 Demo 版 Agent Eval 系统，使其能够：

1. 从 Git 管理的 JSONL Dataset 加载并校验 TEXT Case 与多步骤 Scenario。
2. 为每次 Run、Trial 和 Step 生成稳定的电脑端标识，并以唯一 `correlationId` 关联 TestApp 结果与 Phoenix Trace。
3. 首先跑通半自动真实 Android 评估闭环，再接入 ADB 自动触发。
4. 读取 TestApp 的结构化 AgentResponse 和车辆前后状态快照。
5. 从 Phoenix 查询真实 AIAgent Trace，并标准化为稳定的 `AgentRunResult`。
6. 执行响应、Tool、Safety、状态、轨迹和 Hard Gate 的确定性评分。
7. 对少量软指标执行可配置 LLM Judge；DeepSeek 仅作为默认 Profile，不写死在评分逻辑中。
8. 生成 JSON 与 Markdown Run 报告，并支持 baseline/release 结果提交 Git 和简单历史对比。
9. 为后续 RAG、视觉、真实 SOA、Phoenix Experiment 和 CI 回归保留清晰扩展点，但不在本期实现这些能力。

## 2. 总体架构与实现原则

### 2.1 运行链路

```text
JSONL Dataset
      ↓
RunManager / ScenarioRunner
      ↓
ManualExecutor（首版）或 AdbExecutor（后续增强）
      ↓
Android TestApp Eval Bridge → Android AIAgent
      ↓                         ↓
结构化 AgentResponse/状态      Phoenix Trace
      ↓                         ↓
ResultSource              PhoenixTraceGateway
      └─────────────┬───────────┘
                    ↓
             TraceNormalizer
                    ↓
              AgentRunResult
                    ↓
 Deterministic Graders + Hard Gates + Optional Judge
                    ↓
       JSON Report / Markdown Report / Baseline Compare
```

### 2.2 关键设计决策

- 采用 Python 3.11+、`uv + pyproject.toml` 和 `src/` 包结构；`pyproject.toml` 必须声明 build backend，使项目本身真正安装到 uv 环境。
- 项目作为可安装 CLI 包，命令入口统一为 `aiagent-eval`。
- CLI 首版使用 Python 标准库 `argparse`，不额外引入 Web、桌面 UI 或 CLI 框架。
- Pydantic v2 模型是协议和领域模型的编写源；生成并提交 `schemas/*.schema.json` 作为 Android 两端使用的正式共享 Schema。
- 依赖使用兼容主版本范围并由 `uv.lock` 固定：Pydantic 2.x、Phoenix Client 2.x、Phoenix Evals 3.x；升级主版本必须单独验证 API 和 Schema，不在普通修复中自动跨主版本。
- `schema check` 必须验证已提交 Schema 与当前 Pydantic 模型完全一致，防止代码和协议文件漂移。
- JSONL Dataset 是期望事实的权威来源；Phoenix Dataset 只是可选同步副本。
- TestApp AgentResponse 是对外终态权威，车辆快照是环境事实权威，Phoenix Trace 是内部轨迹权威。
- `gen_ai.output` 只表示某一次模型输出，不能替代最终 AgentResponse。
- 发生来源冲突时生成完整性错误，不允许 Normalizer 静默覆盖。
- 所有 Case 串行运行；本期不实现多设备或多进程并发。
- 开发 Run 默认 1 Trial；baseline/release 默认 3 Trials。
- LLM Judge 默认可关闭；Hard Gate 和确定性评分不依赖 Judge。
- 不保存或提交完整 Raw Trace Dump；只保存经过选择和必要脱敏的标准化事实与证据摘要。

### 2.3 官方技术能力依据

- `uv` 使用 `pyproject.toml` 管理项目、通过 `uv.lock` 固定依赖，并可通过 `[project.scripts]` 提供 CLI 入口。
- `arize-phoenix-client` 提供 Dataset 管理、Span DataFrame 查询和 Annotation 能力。
- `arize-phoenix-evals>=3,<4` 支持 code evaluator、LLM evaluator、结构化 Score 和可配置模型适配；不得使用 Evals 3 已移除的 legacy API。
- Phoenix Client 使用 `from phoenix.client import Client`；不得使用 Phoenix 14 已移除的旧 `px.Client()`/`phoenix.session.client.Client` 入口。
- 本期使用客户端自定义 Run，不使用 Phoenix 原生 Experiment。

参考：

- [uv 项目管理](https://docs.astral.sh/uv/guides/projects/)
- [uv CLI 项目入口配置](https://docs.astral.sh/uv/concepts/projects/config/)
- [Phoenix Python Client](https://arize.com/docs/phoenix/sdk-api-reference/python/arize-phoenix-client)
- [Phoenix Client-Side Evals](https://arize.com/docs/phoenix/evaluation/how-to-evals)
- [Phoenix Judge 模型配置](https://arize.com/docs/phoenix/evaluation/how-to-evals/configuring-the-llm)

## 3. 执行边界

### 3.1 本计划包含

- 初始化独立 Python CLI 工程和独立 Git 仓库。
- 配置、协议模型、JSON Schema、Dataset、Run、Trial、Scenario 和结果模型。
- 半自动执行器、ADB 结果读取抽象和后续自动触发执行器。
- Phoenix Trace 查询、轮询、树重建、标准化和完整性检查。
- 确定性 Grader、Hard Gate、可配置 Judge。
- JSON/Markdown 报告、baseline/release 产物和简单基线对比。
- 单元测试、Fixture 契约测试、可选 Phoenix 集成测试和真实设备验收脚本/清单。
- 项目 README、示例配置和运行说明。

### 3.2 本计划不包含

- 不修改 `AIAgent` 或 `AIAgentTestApp` 源码。
- 不决定 TestApp 最终采用 `run-as` 还是 ADB 可拉取目录；电脑端只提供可配置 ResultSource。
- 不部署、不升级、不配置 Phoenix 服务端。
- 不实现 Phoenix 原生 Experiment。
- 不实现 Web Dashboard、桌面 UI、数据库服务、任务队列或分布式调度。
- 不实现多设备并行、CI 强制门禁、在线持续评估或自动 Prompt 优化。
- 不覆盖 Weather、Vision、RAG、IMAGE、VOICE、CONTROL、主动场景、真实 SOA 或真车验证。
- 不实现多 Judge 投票、复杂加权总分或大规模统计平台。
- 不把 API Key、Phoenix 凭证、设备隐私数据或 Raw Trace 提交 Git。

### 3.3 外部依赖与停止条件

Phase 1、Phase 2 可完全通过 Fixture 独立完成。Phase 3 开始依赖另外两份计划最终提供的 Android 能力：

- TestApp 能接收或载入带 `correlationId` 的 Eval Command。
- TestApp 能写出符合 `eval-result.schema.json` 的结构化结果。
- AIAgent 能回显 `requestId` 与 `clientMessageId/correlationId`。
- AIAgent 能提供车辆状态前后快照和环境修订号。
- Phoenix 根 span `agent.request` 能按 `client_message.id == correlationId` 关联。

若上述外部能力尚未完成，执行者必须：

1. 完成电脑端接口、Fake、Fixture 和契约测试。
2. 输出明确的 Android 阻塞项、需要的字段和人工验收清单。
3. 将 Phase 3 状态标记为“电脑端就绪，真实闭环未验收”。
4. 不得使用手工伪造结果宣称端到端完成。

此外，下列情况属于本项目自身的停止条件：

- `uv` 缺失且用户尚未批准安装。
- 当前 Python 无法解析锁定依赖，需要改变 Python 主/次版本。
- 已部署 Phoenix Server 与锁定的 Phoenix Client 2.x 不兼容。
- Android 实际 command/result JSON 与已提交 Schema 冲突。
- 需要改变 `correlationId`、`requestId`、终态或评分语义。

遇到这些情况，先保留已经完成的代码和测试证据，说明准确阻塞点，再请求用户选择；不得临时换包、降低协议校验或跳过完成门。

### 3.4 Goal 模式执行规则

- 每次只把一个 Phase 设为当前 Goal，避免一次 Goal 跨越全部项目。
- Phase 内按 Task 顺序执行；除非前一 Task 的测试通过，否则不进入依赖它的 Task。
- 每完成一个 Task，更新本计划复选框并记录实际文件与验证结果。
- 遇到会改变协议字段、评分语义、首批 Dataset 范围或 Android 交互方式的问题，停止并询问用户。
- 小型实现细节可按本计划默认值执行，但必须保持最小实现，不新增未要求的抽象。
- 每个 Phase 结束时运行该阶段全部测试，并输出“已完成 / 部分完成 / 外部阻塞”的准确状态。
- 不自动提交 Git；代码、Dataset 和 baseline/release 产物准备好后，由用户决定何时提交。

## 4. 目标目录结构

```text
AIAgent_Eval/
├── AGENTS.md
├── CLAUDE.md
├── README.md
├── .gitignore
├── .python-version
├── pyproject.toml
├── uv.lock
├── docs/
│   ├── protocol.md
│   ├── dataset-authoring.md
│   └── runbook.md
├── config/
│   └── eval.example.toml
├── schemas/
│   ├── protocol-version.json
│   ├── eval-command.schema.json
│   ├── eval-result.schema.json
│   └── vehicle-state.schema.json
├── datasets/
│   └── demo_text_v1.jsonl
├── rubrics/
│   └── response_quality_v1.json
├── runs/
│   ├── local/                    # 开发运行，Git 忽略
│   ├── baseline/                 # 正式基线，可提交
│   └── release/                  # 发布评估，可提交
├── src/aiagent_eval/
│   ├── __init__.py
│   ├── __main__.py
│   ├── cli.py
│   ├── config.py
│   ├── errors.py
│   ├── domain/
│   │   ├── __init__.py
│   │   ├── identifiers.py
│   │   ├── dataset.py
│   │   ├── results.py
│   │   └── runs.py
│   ├── protocol/
│   │   ├── __init__.py
│   │   ├── models.py
│   │   └── schema_export.py
│   ├── datasets/
│   │   ├── __init__.py
│   │   ├── loader.py
│   │   └── phoenix_sync.py
│   ├── executor/
│   │   ├── __init__.py
│   │   ├── base.py
│   │   ├── fixture.py
│   │   ├── manual.py
│   │   ├── adb.py
│   │   └── result_source.py
│   ├── trace/
│   │   ├── __init__.py
│   │   ├── phoenix_gateway.py
│   │   ├── normalizer.py
│   │   └── integrity.py
│   ├── graders/
│   │   ├── __init__.py
│   │   ├── base.py
│   │   ├── response.py
│   │   ├── tool.py
│   │   ├── safety.py
│   │   ├── state.py
│   │   ├── trajectory.py
│   │   ├── hard_gates.py
│   │   └── suite.py
│   ├── judge/
│   │   ├── __init__.py
│   │   └── phoenix_judge.py
│   ├── runner/
│   │   ├── __init__.py
│   │   ├── run_manager.py
│   │   ├── scenario_runner.py
│   │   └── artifact_store.py
│   └── report/
│       ├── __init__.py
│       ├── generator.py
│       └── baseline.py
└── tests/
    ├── conftest.py
    ├── fixtures/
    │   ├── datasets/
    │   ├── results/
    │   └── traces/
    ├── unit/
    ├── contract/
    └── integration/
```

说明：目录结构是本期上限。若某个包只有一个很小的实现文件，不继续拆分更多层级；不创建空的“未来扩展”模块。

## 5. 统一数据与协议口径

### 5.1 ID 所有权

| ID | 生成方 | 是否跨系统 | 用途 |
|---|---|---|---|
| `runId` | AIAgent_Eval | 否 | 一次批量评估 |
| `caseId` | Dataset | 否 | 稳定案例标识 |
| `trialId` | AIAgent_Eval | 否 | 同一 Case 的一次执行 |
| `scenarioId` | Dataset/RunManager | 否 | 多步骤场景标识 |
| `stepId` | Dataset/RunManager | 否 | Scenario 内步骤标识 |
| `correlationId` | AIAgent_Eval | 是 | 电脑端、TestApp、AgentResponse、Trace 的唯一关联键 |
| `clientMessageId` | TestApp 映射 | 是 | 在 Android 现有字段中承载 `correlationId` |
| `requestId` | TestApp | 是 | AIAgent 运行中请求与取消标识；电脑端不预先指定 |
| `traceId` | OpenTelemetry | 是 | Phoenix Trace 标识 |
| `confirmationId` | AIAgent | 是 | 二次确认状态机标识 |

补充规则：每一条发往 TestApp 的 Eval Command，包括环境、状态、会话和版本操作，都拥有独立 `correlationId`，用于结果文件幂等和诊断；只有 `SEND_TEXT` 的 correlationId 会映射为 `clientMessageId` 并要求关联 Phoenix Trace。`CANCEL_REQUEST` 自身有新的 correlationId，同时在 payload 中携带被取消请求的 `targetCorrelationId`。

### 5.2 协议模型

`protocol/models.py` 至少定义：

- `ProtocolVersion`：major、minor、schemaHash。
- `EvalCommandEnvelope`：protocolVersion、correlationId、action、payload、createdAt、timeoutMs（可选）、metadata（可选）。
- `EvalAction`：`ACQUIRE_ENVIRONMENT`、`RESET_STATE`、`APPLY_STATE`、`READ_STATE`、`SEND_TEXT`、`CANCEL_REQUEST`、`CREATE_SESSION`、`SWITCH_SESSION`、`DELETE_SESSION`、`GET_VERSION`、`RELEASE_ENVIRONMENT`。
- `AgentRequestPayload`：userId、sessionId、personaId、text、inputType，禁止电脑端 requestId 字段。
- `AgentResponsePayload`：requestId、sessionId、success、text、errorType、timestamp、userId、personaId、status、errorDetail、clientMessageId。
- `EvalOperationResult`：action、success、status、data、error，用于环境、状态、会话、取消和版本读取等非 AgentResponse 操作。`data` 不是任意无约束字典，应按 action 使用判别联合：环境操作返回 leaseActive/状态摘要但绝不返回 leaseToken；状态操作返回 snapshot；会话操作返回 sessionId/操作状态；取消返回 targetCorrelationId/accepted/status；版本操作返回 versionFingerprint。
- `VehicleStateSnapshot`：schemaVersion、environmentRevision、capturedAt、systems。
- `EvalResultEnvelope`：protocolVersion、correlationId、bridgeState、action、requestId、agentResponse、operationResult、beforeVehicleState、afterVehicleState、versionFingerprint、timestamps、bridgeError、metadata（可选）。
- `BridgeState`：`PENDING`、`RUNNING`、`TERMINAL`。
- `VersionFingerprint`：TestApp/AIAgent 版本、APK/build 标识、Prompt 摘要、模型、Trace 模式、状态 Schema 版本；未知值显式为 null，不伪造默认值。

车辆 `systems` 初版固定一级键：`ac`、`door`、`window`、`seat`、`speed`、`chassis`、`fragrance`、`dms`。各系统内部字段保留为受 JSON 类型约束的对象，以避免电脑端复制全部 Android POJO；Dataset 通过点路径断言具体字段。

跨字段不变量必须进入 Pydantic 校验和契约测试：

- `SEND_TEXT` 的 TERMINAL 结果若包含 AgentResponse，则 `agentResponse.clientMessageId == correlationId`。
- envelope.requestId 与 agentResponse.requestId 同时存在时必须相同；非请求类 operation 的 requestId 必须为 null。
- `CANCEL_REQUEST.operationResult.data.targetCorrelationId` 指向目标 SEND_TEXT correlationId，不能等于取消命令自身 correlationId。
- TERMINAL 必须且只能以真实 AgentResponse、结构化 operationResult 或 bridgeError 表达结果；PENDING/RUNNING 不能伪装业务成功。
- beforeVehicleState/afterVehicleState 仅在相关动作或 SEND_TEXT 中出现，revision 不能倒退。
- leaseToken 是 Android 内部能力凭据，禁止进入跨电脑协议、日志、Dataset、Run 产物和 Trace。

### 5.3 Dataset 领域模型

`domain/dataset.py` 使用 Pydantic 判别联合定义：

- `EvalEntry = EvalCase | EvalScenario`，以 `kind` 区分。
- 公共字段：id、version、title、capability、tags、enabled、description。
- `EvalCase`：request、initialState、expectation、judgeRubrics、trialPolicy。
- `EvalScenario`：sessionPolicy、initialState、steps、expectation、trialPolicy。
- `ScenarioStep`：stepId、action、request、delay/timeout、stepExpectation。
- `Expectation`：response、tools、safety、state、trajectory、hardGates。
- `ResponseExpectation`：terminalStatus、success、errorType、containsAny、containsAll、forbiddenText、regex。
- `ToolExpectation`：mode（EXACT/SUBSET/NONE）、toolNames、argumentMatchers、dispatchSuccess、maxCalls。
- `SafetyExpectation`：decision、reasonCode、requiresConfirmation、confirmationContinuity。
- `StateExpectation`：before/after 点路径断言、unchangedPaths。
- `TrajectoryExpectation`：requiredSpans、forbiddenSpans、maxIterations、traceRequired。
- `TrialPolicy`：devTrials 默认 1、baselineTrials 默认 3，可由单条记录覆盖。

所有枚举值和字段名进入 JSON Schema；未知字段默认拒绝，以尽早暴露 Dataset 拼写错误。

### 5.4 标准化结果模型

`domain/results.py` 至少定义：

- `RawSpan`：Phoenix 行数据的内部稳定表达。
- `AgentRunResult`：AgentResponse、前后状态、TraceId、根请求、LLM calls、Tool executions、Safety、Dispatch、Memory、latency、token、iterations、warnings。
- `ToolExecutionFact`：toolName、arguments、safetyDecision、safetyReasonCode、dispatchAttempted、dispatchSuccess、writebackSuccess、output、spanIds。
- `TraceIntegrityResult`：uniqueRoot、terminalSpanPresent、correlationMatched、requestIdMatched、treeComplete、errors。
- `GradeResult`：grader、status、summary、evidence、hardGate。
- `JudgeResult`：status、rubricVersion、provider、model、label、score、explanation、error。
- `TrialResult`：Case/Scenario、各 Step、标准化事实、评分、最终状态。
- `CaseStatus`：`PASS`、`FAIL`、`INFRA_ERROR`、`TRACE_INCOMPLETE`、`UNSCORABLE`、`SKIPPED`。

## Phase 1：工程骨架、配置、协议与 Dataset

### Task 1.1：初始化独立 Python CLI 工程

**Files**

- Create: `README.md`
- Create: `.python-version`
- Create: `.gitignore`
- Create: `pyproject.toml`
- Create: `uv.lock`
- Create: `src/aiagent_eval/__init__.py`
- Create: `src/aiagent_eval/__main__.py`
- Create: `src/aiagent_eval/cli.py`
- Create: `tests/conftest.py`
- Create: `tests/unit/test_cli_smoke.py`

**工作步骤**

- [ ] 先执行本计划第 0.2 节机器检查；若 `uv` 不可用，暂停并向用户申请安装授权。获批后使用 uv 官方安装方式，记录实际 uv 版本，不把安装脚本或用户级工具文件写入项目。
- [ ] 检查目标目录不是其他 Git 仓库的子目录；若尚无 `.git`，初始化独立 Git 仓库。
- [ ] 使用 Python 3.11+ 初始化可安装的 packaged application，保留现有 `AGENTS.md`、`CLAUDE.md` 和 `docs/`；不能生成一个只有 `src/` 目录却没有 build backend 的不可安装项目。
- [ ] 在 `pyproject.toml` 中声明项目名 `aiagent-eval`、`requires-python >=3.11`、由 uv packaged project 生成并锁定的 `[build-system]`，以及 `[project.scripts] aiagent-eval = "aiagent_eval.cli:main"`。
- [ ] 添加运行依赖：`pydantic>=2,<3`、pydantic-settings、`arize-phoenix-client>=2,<3`、`arize-phoenix-evals>=3,<4`、pandas、openai；具体小版本由首次成功解析的 `uv.lock` 固定。
- [ ] 添加开发依赖：pytest、pytest-cov、ruff、jsonschema。
- [ ] 在 `pyproject.toml` 注册 `integration`、`device`、`judge` pytest markers，保证默认测试和显式集成测试边界稳定。
- [ ] 生成并提交 `uv.lock`；禁止手工编辑 lockfile。
- [ ] `.gitignore` 忽略 `.venv/`、缓存、coverage、IDE、本地配置、`runs/local/`、Raw Trace、临时 ADB 文件和凭证。
- [ ] 保留 `runs/baseline/`、`runs/release/` 可被 Git 跟踪，但不自动提交生成结果。
- [ ] `__main__.py` 只委托 `cli.main()`，保证 `uv run python -m aiagent_eval` 与 `uv run aiagent-eval` 等价。
- [ ] 增加最小 CLI smoke test，验证 `--help` 正常退出，避免 pytest 因“没有测试”返回错误码。
- [ ] README 初版只说明定位、安装、配置、命令入口、当前边界和 Phase 状态，不提前声称真实设备闭环完成。

**验证**

```powershell
uv sync
uv run aiagent-eval --help
uv run python -m aiagent_eval --help
uv run pytest -q
uv run ruff check .
uv build
```

Expected：依赖可锁定，当前项目可被 uv 安装并成功构建 wheel/sdist，两个 CLI 入口退出码为 0，测试骨架可运行，Ruff 无错误。

**任务边界**

- 不添加数据库、Web、Rich/Typer、Docker 或 CI 配置。
- 不创建真实 `.env` 或写入任何 API Key。

### Task 1.2：实现集中配置与运行前诊断

**Files**

- Create: `config/eval.example.toml`
- Create: `src/aiagent_eval/config.py`
- Create: `src/aiagent_eval/errors.py`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_config.py`
- Test: `tests/unit/test_doctor.py`

**工作步骤**

- [ ] 定义 `EvalSettings`，集中包含 `phoenix`、`android`、`judge`、`run` 四组配置。
- [ ] TOML 只保存非敏感默认值；密钥只通过配置指定的环境变量名读取。
- [ ] Phoenix 配置至少包含 baseUrl、projectName、pollIntervalSeconds、traceTimeoutSeconds、lookbackSeconds。
- [ ] Android 配置至少包含 adbPath、serial、testAppPackage、commandComponent、resultReadStrategy、resultPath、resultTimeoutSeconds。
- [ ] Judge 配置至少包含 enabled、provider、client、model、baseUrl、apiKeyEnv、timeoutSeconds、maxRetries、temperature、maxTokens、thinkingMode、rubricVersion。
- [ ] Run 配置至少包含 artifactRoot、devTrials=1、baselineTrials=3、releaseTrials=3、rawContentPolicy。
- [ ] 配置优先级固定为：CLI 参数 > 环境变量 > 指定 TOML > 代码安全默认值。
- [ ] 缺少 Judge Key 时，仅禁用 Judge 或返回 `JUDGE_SKIPPED`；不得阻止确定性 Eval。
- [ ] 缺少 Phoenix 地址、ADB 或设备时，`doctor` 明确输出哪个能力不可用，不输出堆栈和密钥；ADB 检查必须支持绝对 `adbPath`，不能只依赖 PATH。
- [ ] `doctor` 输出 Python、uv、Phoenix Client/Evals 和 OpenAI SDK 的实际版本；若 Phoenix Client 不在 2.x 或 Evals 不在 3.x，直接标为不兼容。
- [ ] 实现 `aiagent-eval doctor --config config/eval.toml`，检查 Python、配置、ADB 可执行文件、设备列表、Phoenix 连通性和 Judge 配置；各检查独立汇报。
- [ ] `doctor` 不修改设备、不执行 Agent 请求、不创建 Phoenix Dataset。

**测试重点**

- 配置优先级正确。
- 密钥值不会出现在 `repr`、错误消息或 doctor 输出。
- Judge disabled 时不要求 Key。
- 无 ADB/Phoenix 时返回结构化失败项而非程序崩溃。
- `adbPath=D:\code\android\forSdk\Sdk\platform-tools\adb.exe` 时可以定位当前 Android SDK 中的 ADB；路径不存在时给出配置错误而不是回退到任意同名程序。

### Task 1.3：实现共享协议模型与 Schema 生成

**Files**

- Create: `src/aiagent_eval/protocol/__init__.py`
- Create: `src/aiagent_eval/protocol/models.py`
- Create: `src/aiagent_eval/protocol/schema_export.py`
- Create: `schemas/protocol-version.json`
- Create: `schemas/eval-command.schema.json`
- Create: `schemas/eval-result.schema.json`
- Create: `schemas/vehicle-state.schema.json`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/contract/test_protocol_models.py`
- Test: `tests/contract/test_schema_export.py`

**工作步骤**

- [ ] 按第 5.2 节建立 Pydantic 协议模型，统一 camelCase JSON alias，Python 内部使用 snake_case。
- [ ] 所有协议时间使用带时区 ISO-8601 UTC；Android epoch millis 只作为 payload 原始字段保留。
- [ ] `EvalCommandEnvelope` 明确禁止 `requestId`，测试电脑端不能主动注入 AIAgent requestId。
- [ ] 校验 `correlationId` 非空、长度受限、仅允许 UUID 或约定安全字符，避免被用于任意文件路径。
- [ ] `EvalResultEnvelope` 仅在存在 AgentResponse 时校验其 `clientMessageId == correlationId`；环境、状态、会话、取消和版本 operation 不要求虚构 clientMessageId。
- [ ] 校验 envelope.requestId 与 AgentResponse.requestId 一致、控制 operation 不携带 requestId、取消结果 targetCorrelationId 不等于自身 correlationId。
- [ ] `bridgeState == TERMINAL` 时要求 AgentResponse、operationResult 或 bridgeError 至少存在一个。
- [ ] 为 operationResult 建立按 action 判别的数据模型，测试 leaseToken 无法进入任何电脑端 Schema。
- [ ] 状态快照要求 environmentRevision 非负，systems 一级键固定，内部值必须是 JSON object。
- [ ] 实现 `aiagent-eval schema export`，以稳定排序和统一缩进原子写入四份 Schema 文件。
- [ ] 实现 `aiagent-eval schema check`，在临时内存生成 Schema 并逐字节比较已提交文件；漂移时退出非零并提示需重新 export。
- [ ] `protocol-version.json` 保存 major/minor 和其他三份 Schema 内容 hash。
- [ ] 使用 `jsonschema` 对最小合法样例、完整样例和非法样例再次验证，确保生成 Schema 能被非 Python 实现消费。

**验收命令**

```powershell
uv run aiagent-eval schema export
uv run aiagent-eval schema check
uv run pytest tests/contract/test_protocol_models.py tests/contract/test_schema_export.py -q
```

Expected：Schema 无漂移；非法 requestId、错误 correlation、错误终态、泄露 leaseToken、错误 operation data 和非法状态对象均被拒绝。

### Task 1.4：实现 Dataset 模型、加载器和首批案例

**Files**

- Create: `src/aiagent_eval/domain/__init__.py`
- Create: `src/aiagent_eval/domain/identifiers.py`
- Create: `src/aiagent_eval/domain/dataset.py`
- Create: `src/aiagent_eval/datasets/__init__.py`
- Create: `src/aiagent_eval/datasets/loader.py`
- Create: `datasets/demo_text_v1.jsonl`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_identifiers.py`
- Test: `tests/unit/test_dataset_loader.py`
- Fixture: `tests/fixtures/datasets/invalid_*.jsonl`

**工作步骤**

- [ ] 按第 5.3 节实现 Dataset 判别联合和 Expectation 模型，禁止未知字段。
- [ ] `identifiers.py` 只负责 run/trial/correlation 生成，不生成 requestId、traceId 或 confirmationId。
- [ ] correlationId 每个真实请求/Step 唯一；Scenario 的多个 SEND_TEXT Step 不复用同一 correlationId。
- [ ] JSONL Loader 逐行解析，错误中包含文件、行号、entry id 和字段路径。
- [ ] 校验 id 全局唯一、Scenario stepId 局部唯一、启用条目至少有一个期望项、baselineTrials 大于 0。
- [ ] 校验工具名必须来自本期维护的稳定 Tool 词表；首版至少包含 `set_ac_status`、`set_ac_drive_temp`、`set_fl_window_status`、`set_door_lock`、`set_chassis_mode`。
- [ ] Tool 参数匹配以 Trace 当前真实键 `arg0/arg1...` 为准；不要在电脑端假设尚未实现的命名参数。
- [ ] 实现 `aiagent-eval dataset validate <path>`，输出总数、Case/Scenario 数、分类和错误。
- [ ] 首批 Dataset 控制在 12 条左右，覆盖：普通对话、空调开关、主驾温度、左前车窗、允许锁门、行驶中拒绝解锁、静止解锁二次确认、行驶中拒绝底盘模式、静止切换底盘模式确认、无 Tool 却声称成功、取消/超时、少量 Session/Memory 隔离。
- [ ] Android 状态字段尚未最终确认的 Case 可先标记 `enabled=false` 或使用明确的协议点路径占位，并在 Phase 3 前由 Android Schema 契约测试解锁；不得猜测字段后直接进入 baseline。

**测试重点**

- 空行允许，损坏 JSON、重复 id、未知字段、错误工具名和无期望条目必须失败。
- Dataset 加载顺序稳定，同一文件 hash 可重复生成。
- `devTrials=1`、`baselineTrials=3` 默认值正确。

### Phase 1 完成门

```powershell
uv run ruff check .
uv run pytest tests/unit tests/contract -q
uv run aiagent-eval schema check
uv run aiagent-eval dataset validate datasets/demo_text_v1.jsonl
uv run aiagent-eval doctor --config config/eval.example.toml
uv build
```

完成标准：工程可安装、CLI 可用、协议和 Dataset 可独立校验；此阶段不要求 Android、Phoenix 或 Judge 在线。

说明：无外部环境时 `doctor` 可以返回非零退出码，但必须完整输出 ADB/Phoenix/Judge 各项诊断；Phase 1 验收的是诊断行为正确，不要求所有外部检查为绿色。

## Phase 2：离线 Trace 标准化、确定性评分与报告闭环

### Task 2.1：实现标准化事实与 Run 结果模型

**Files**

- Create: `src/aiagent_eval/domain/results.py`
- Create: `src/aiagent_eval/domain/runs.py`
- Fixture: `tests/fixtures/results/agent_success.json`
- Fixture: `tests/fixtures/results/agent_denied.json`
- Fixture: `tests/fixtures/traces/chat_success.json`
- Fixture: `tests/fixtures/traces/tool_success.json`
- Fixture: `tests/fixtures/traces/safety_deny.json`
- Fixture: `tests/fixtures/traces/confirmation_two_step.json`
- Test: `tests/unit/test_result_models.py`

**工作步骤**

- [ ] 按第 5.4 节实现不可混淆的原始事实、标准化事实、评分和最终结果模型。
- [ ] 区分 `AgentResponse.text`、各轮 `gen_ai.output` 和 Tool output，字段命名不得统一压成一个 output。
- [ ] 所有“未知”和“缺失”使用 null/warning/error 表达，不用 false、0 或空字符串假装真实值。
- [ ] `TrialResult` 保存 correlationId、requestId、traceId 三种不同标识，并验证各自所有权。
- [ ] Fixture 来源于当前 AIAgent Trace 结构：`agent.request → agent.loop → agent.iteration → gen_ai.chat → tool.execute`，以及根节点直接子 span `response.dispatch`。
- [ ] Fixture 覆盖多轮 Tool 回写、Safety deny、缺少 response.dispatch、重复根 span 和 requestId 冲突。
- [ ] Fixture 只保存测试所需最小内容，不复制真实用户对话或凭证。

### Task 2.2：实现 Phoenix Gateway、轮询与 Trace Normalizer

**Files**

- Create: `src/aiagent_eval/trace/__init__.py`
- Create: `src/aiagent_eval/trace/phoenix_gateway.py`
- Create: `src/aiagent_eval/trace/normalizer.py`
- Create: `src/aiagent_eval/trace/integrity.py`
- Test: `tests/unit/test_trace_normalizer.py`
- Test: `tests/unit/test_trace_integrity.py`
- Test: `tests/integration/test_phoenix_gateway.py`

**工作步骤**

- [ ] 在 `PhoenixTraceGateway` 内部通过 `from phoenix.client import Client` 封装 Phoenix Client 2.x，业务层不得直接依赖 DataFrame 列名或 SDK 生成类型。
- [ ] 查询入口为 `wait_for_trace(correlation_id, started_at, timeout)`。
- [ ] 首版使用 Phoenix Client Span 查询接口获取有限时间窗数据，再按根 span 属性 `client_message.id` 精确过滤；不得按用户文本模糊关联。
- [ ] 若目标 Phoenix Server/Client 支持按 attributes 服务端过滤，可优先使用 `client_message.id` 属性过滤；否则使用有限时间窗 DataFrame 查询后本地精确过滤。两条路径必须返回相同 RawSpan，不允许业务层感知 SDK 差异。
- [ ] 要求恰好一个 `agent.request` 根 span；找到后用 traceId 拉取/筛选完整 Span 集合。
- [ ] 轮询间隔和超时来自配置；未出现 `response.dispatch` 时继续等待，超时标记 `TRACE_INCOMPLETE`。
- [ ] 将 Phoenix DataFrame 行转换为内部 `RawSpan`，集中处理 attributes 展开、时间、parentSpanId、status 和事件字段。
- [ ] Normalizer 按 span name 和 parentSpanId 重建树，不依赖 DataFrame 当前排序。
- [ ] 映射当前稳定 span：`agent.request`、`agent.loop`、`agent.iteration`、`context.prepare`、`context.assemble`、`context.toolset`、`gen_ai.chat`、`tool.execute`、`tool.safety_check`、`tool.dispatch`、`tool.result_writeback`、`memory.extract`、`memory.compress`、`response.dispatch`。
- [ ] 映射当前稳定属性：`request.id`、`client_message.id`、`session.id`、`gen_ai.output`、`gen_ai.tool_calls`、token、`tool.name`、`tool.arguments`、Safety、Dispatch、Writeback、response 和错误属性。
- [ ] 允许同一请求多个 iteration、多个 `gen_ai.chat` 和多个 `tool.execute`；禁止只取最后一轮导致轨迹丢失。
- [ ] `TraceIntegrityChecker` 检查 correlation、requestId、根唯一性、终态 span、父子引用、工具子阶段顺序和 Trace 完整性。
- [ ] 若 AgentResponse 与 Trace 终态冲突，保留双方事实并产生 `FACT_CONFLICT`，不得以 Trace 覆盖 AgentResponse。
- [ ] Integration 测试只有设置 Phoenix 地址时才运行；默认测试套件使用 Fake Gateway。

**验收重点**

- Tool-heavy Fixture 可解析多个工具和第二轮 LLM。
- `gen_ai.request.tool_count > 0` 但无 `tool.execute` 时，结果必须表示“工具被提供但未调用”。
- `memory.compress` 为条件节点，缺失不视为普通对话 Trace 错误。

### Task 2.3：实现确定性 Grader 和 Hard Gate

**Files**

- Create: `src/aiagent_eval/graders/__init__.py`
- Create: `src/aiagent_eval/graders/base.py`
- Create: `src/aiagent_eval/graders/response.py`
- Create: `src/aiagent_eval/graders/tool.py`
- Create: `src/aiagent_eval/graders/safety.py`
- Create: `src/aiagent_eval/graders/state.py`
- Create: `src/aiagent_eval/graders/trajectory.py`
- Create: `src/aiagent_eval/graders/hard_gates.py`
- Create: `src/aiagent_eval/graders/suite.py`
- Test: `tests/unit/graders/test_*.py`

**公共接口**

```text
Grader.grade(entry, agent_run_result) -> GradeResult
HardGate.evaluate(entry, agent_run_result, grade_results) -> list[GradeResult]
```

**工作步骤**

- [ ] `ResponseGrader` 检查 success、status、errorType、包含/禁止文本和正则；文本软质量不在此判断。
- [ ] `ToolGrader` 支持 EXACT/SUBSET/NONE，检查真实 `tool.execute`、参数 matcher、调用次数和 Dispatch 成功。
- [ ] 参数 matcher 首版支持 typed equality、数值范围和字符串枚举，不实现任意 Python 表达式。
- [ ] `SafetyGrader` 检查 decision、reasonCode、是否出现 confirmationId，以及 Scenario 两步 confirmationId 连续性。
- [ ] `StateGrader` 使用受限点路径读取 before/after systems；只支持字段访问和数组索引，不执行 JSONPath 脚本。
- [ ] `TrajectoryGrader` 检查 required/forbidden spans、maxIterations、终态、Tool 子阶段和 TraceIntegrity。
- [ ] `GraderSuite` 固定执行顺序：完整性 → response → tool → safety → state → trajectory → Hard Gate。
- [ ] 单个 Grader 代码异常返回 `ERROR` 并使 Case `UNSCORABLE`，不能吞掉异常后判 PASS。
- [ ] Hard Gate 至少实现：Safety deny 后 Dispatch、未确认高风险执行、未授权工具、需要 Tool 却无 ToolCall 且回复成功、Dispatch 失败却回复成功、最终状态矛盾、timeout/cancel 后迟到成功、高风险重复执行、显式 Memory 泄露断言。
- [ ] Hard Gate 失败直接使 Case FAIL，Judge 分数和其他 PASS 不得抵消。
- [ ] “回复声称成功”首版只使用 Dataset 的 `requiresToolExecution=true` 与 AgentResponse.success 联合判断，不引入复杂自然语言分类器。

**测试矩阵**

- 每个 Grader 至少包含 PASS、FAIL、缺失事实、错误输入四类测试。
- Hard Gate 每条规则至少有命中与不命中测试。
- 浮点状态比较允许 Dataset 指定 tolerance；其他类型严格相等。

### Task 2.4：实现 RunManager、ScenarioRunner 和原子产物存储

**Files**

- Create: `src/aiagent_eval/runner/__init__.py`
- Create: `src/aiagent_eval/runner/run_manager.py`
- Create: `src/aiagent_eval/runner/scenario_runner.py`
- Create: `src/aiagent_eval/runner/artifact_store.py`
- Create: `src/aiagent_eval/executor/__init__.py`
- Create: `src/aiagent_eval/executor/base.py`
- Create: `src/aiagent_eval/executor/fixture.py`
- Test: `tests/unit/test_run_manager.py`
- Test: `tests/unit/test_scenario_runner.py`
- Test: `tests/unit/test_artifact_store.py`
- Test: `tests/unit/test_fixture_executor.py`

**工作步骤**

- [ ] RunManager 根据 runKind 选择默认 Trial 数，允许 CLI 明确覆盖。
- [ ] 在 `executor/base.py` 定义最小 Executor 协议，使 fixture、manual、adb 三种模式返回同一个 EvalResultEnvelope。
- [ ] 每个 Trial 和每个 SEND_TEXT Step 生成新 correlationId；电脑端编排 ID 不全部写入 Android 请求。
- [ ] 执行顺序固定串行，避免车辆状态和 Session 并发污染。
- [ ] ScenarioRunner 只解释已定义 Step：SEND_TEXT、CANCEL_ACTIVE、WAIT；状态 reset/apply/read 作为 Case/Scenario 生命周期动作统一处理。
- [ ] `FixtureExecutor` 从 CLI 指定的 fixture bundle 读取 ResultEnvelope 与 RawSpan，只服务 Phase 2 离线闭环和单元测试；Dataset 不写入测试专用文件路径。
- [ ] fixture 模式禁止生成 baseline/release Run，避免把模拟执行结果误当成正式真实评估。
- [ ] Scenario 内共享声明的 session，普通 Case 每个 Trial 使用独立 Eval session。
- [ ] 每个 Trial 完成后立即原子写入中间结果，避免进程失败丢失整批 Run。
- [ ] `ArtifactStore` 先写 `.tmp`，flush 后 rename；禁止原地写坏正式 JSON。
- [ ] Run manifest 保存 Dataset hash、配置摘要、Eval git SHA、协议版本、Grader/Rubric 版本、运行时间和设备/Phoenix 指纹。
- [ ] 若仓库还没有 commit 或工作树有修改，manifest 分别记录 gitSha=null/dirty=true，不伪造 SHA；baseline/release 运行前必须提示当前代码是否可复现。
- [ ] `--resume` 只允许 Dataset hash、协议版本和关键配置一致的未完成 Run；不一致时拒绝继续。
- [ ] resume 必须复用已经持久化的 correlationId。对已发送但终态未知的 Android 命令，先按原 correlationId 查询结果；无法确认时停止并请求人工决策，禁止生成新 correlationId 后重放可能有副作用的 Tool/取消/会话动作。
- [ ] 结果目录固定：dev → `runs/local/<runId>`，baseline → `runs/baseline/<runId>`，release → `runs/release/<runId>`。
- [ ] 不在产物中保存 API Key、完整配置密钥或 Raw Span DataFrame。

### Task 2.5：实现 JSON/Markdown 报告和基线对比

**Files**

- Create: `src/aiagent_eval/report/__init__.py`
- Create: `src/aiagent_eval/report/generator.py`
- Create: `src/aiagent_eval/report/baseline.py`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_report_generator.py`
- Test: `tests/unit/test_baseline_compare.py`

**工作步骤**

- [ ] 每个 Run 生成 `manifest.json`、`results.json`、`failures.json` 和 `report.md`。
- [ ] JSON 使用稳定排序和明确 schemaVersion，便于 Git diff。
- [ ] Markdown 报告包含 Run 摘要、能力分类通过率、Case/Trial、各 Grader、Hard Gate、Judge、latency/token/iteration、TraceId 和失败原因。
- [ ] 对 `INFRA_ERROR`、`TRACE_INCOMPLETE`、`UNSCORABLE` 单独统计，不混入普通 FAIL。
- [ ] 多 Trial Case 同时报告通过次数与稳定通过率，不计算复杂置信区间。
- [ ] 实现 `aiagent-eval compare --baseline <run> --candidate <run>`，按 caseId 和 grader 名比较新增失败、恢复通过、状态变化和简单性能变化。
- [ ] Case 缺失、Dataset 版本不同或协议 major 不同必须在比较报告顶部警告。
- [ ] Markdown 由 Python 代码直接生成，不引入模板引擎。
- [ ] 报告只引用 TraceId，不内嵌完整 Prompt、Memory 或工具大结果。

### Phase 2 完成门

```powershell
uv run ruff check .
uv run pytest tests/unit tests/contract -q
uv run aiagent-eval dataset validate datasets/demo_text_v1.jsonl
uv run aiagent-eval run --dataset datasets/demo_text_v1.jsonl --executor fixture --fixture-bundle <fixture-dir> --case <fixture-case-id>
uv run aiagent-eval compare --baseline <fixture-run-a> --candidate <fixture-run-b>
```

完成标准：不依赖 Android/Phoenix 在线环境，使用 Fixture 可以完成 Dataset → Normalize → Grade → Hard Gate → Report 的完整离线闭环。

## Phase 3：真实 Android 半自动最小评估闭环

### Task 3.1：实现 Executor 与 ResultSource 抽象

**Files**

- Modify: `src/aiagent_eval/executor/base.py`
- Create: `src/aiagent_eval/executor/manual.py`
- Create: `src/aiagent_eval/executor/result_source.py`
- Test: `tests/unit/test_manual_executor.py`
- Test: `tests/contract/test_android_result_contract.py`

**公共接口**

```text
Executor.execute(command, execution_context) -> EvalResultEnvelope
ResultSource.wait_for_result(correlation_id, timeout) -> EvalResultEnvelope
```

**工作步骤**

- [ ] `ManualExecutor` 为当前 Step 生成并保存 `command.json`，终端显示 caseId、stepId、correlationId、用户输入和操作提示。
- [ ] ManualExecutor 同时输出 command.json 绝对路径和 URL-safe/no-wrap Base64 文本；用户只负责在 TestApp EvalBridgeActivity 导入并确认触发，电脑端仍自动等待结构化结果和 Trace。
- [ ] `FileResultSource` 支持读取本地导入结果，作为契约调试和紧急人工通道。
- [ ] `AdbResultSource` 的接口在本 Task 定义，但具体读取策略在 Task 3.3 接入。
- [ ] ResultSource 只按 correlationId 读取目标文件，校验文件名安全、协议版本、终态和内容 ID。
- [ ] PENDING/RUNNING 状态继续轮询，TERMINAL 才返回；过期旧结果不得被误认成本轮结果。
- [ ] 同一 correlationId 出现两个不同 TERMINAL 结果时返回 `RESULT_CONFLICT`。
- [ ] TestApp ResultStore 只是传输副本；电脑端不得补写或修改 AgentResponse 事实。

### Task 3.2：接入半自动 Case/Scenario 生命周期

**Files**

- Modify: `src/aiagent_eval/runner/run_manager.py`
- Modify: `src/aiagent_eval/runner/scenario_runner.py`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_manual_run_flow.py`

**工作步骤**

- [ ] 实现 `aiagent-eval run --executor manual --dataset ... [--case ...] [--trials ...]`。
- [ ] 每个 Run 启动时先执行 GET_VERSION 并写入 manifest；每个 SEND_TEXT 结果携带的版本指纹必须与 Run 指纹兼容，不一致时停止该 Run。
- [ ] 普通 Case 每个 Trial 的流程固定为：获取 Eval 环境 → reset → apply initial state → 创建独立 Eval session → 切换 session → before snapshot → SEND_TEXT → AgentResponse → after snapshot → 按策略删除 session → 释放环境。
- [ ] Scenario 在一次环境租约和声明的 session 下逐 Step 执行，二次确认使用两个独立 correlationId；Scenario 结束后按 sessionPolicy 清理会话。
- [ ] ScenarioStep 的 `CANCEL_ACTIVE` 是电脑端领域动作，发送到 TestApp 时必须映射成协议 `CANCEL_REQUEST`，payload 只携带当前活动 SEND_TEXT 的 targetCorrelationId；`WAIT` 只在电脑端计时，不发送 Android 命令。
- [ ] 每个外部操作都有独立超时和结构化错误，不用一个总 timeout 混淆 ADB、Agent、Trace 和 Judge 超时。
- [ ] 异常路径必须尝试释放 Eval 环境；释放失败写入 manifest warning。
- [ ] 用户中断时保存已完成 Trial、当前 Step、已生成 correlationId 和是否已发送；resume 遵守 Task 2.4 的不重放规则。
- [ ] 取消 Scenario 只向 TestApp 发送 `targetCorrelationId`；TestApp 从内部执行记录映射真实 requestId 后调用取消接口，电脑端既不预造也不直接传递 requestId。

### Task 3.3：实现真实 ADB 结果读取和 Phoenix 查询集成

**Files**

- Create: `src/aiagent_eval/executor/adb.py`
- Modify: `src/aiagent_eval/executor/result_source.py`
- Modify: `src/aiagent_eval/trace/phoenix_gateway.py`
- Test: `tests/integration/test_adb_result_source.py`
- Test: `tests/integration/test_real_trace_query.py`

**工作步骤**

- [ ] 用 `subprocess.run([...], shell=False)` 封装 ADB，禁止拼接不可信 shell 字符串。
- [ ] 支持明确 serial；发现多个设备但未指定 serial 时立即失败。
- [ ] `AdbResultSource` 读取策略来自配置，不在业务代码写死目录。
- [ ] 初始实现允许 `run_as` 与 `pull` 两种枚举，但第一候选是 TestApp 计划约定的 `run_as`；只有模拟器实测成功后才把它写成示例配置默认值。
- [ ] `run_as` 使用参数数组执行等价于 `adb -s <serial> exec-out run-as com.hirain.aiagent.test cat files/eval-results/<correlationId>.json` 的命令；禁止通过 PowerShell/cmd 拼接。`pull` 只读取用户明确批准的 app-specific 结果目录。
- [ ] ADB stderr、exit code、超时和文件不存在分别映射为结构化 InfraError。
- [ ] 读取后先在内存校验完整 JSON，再交给 Pydantic；不在源目录修改或删除 TestApp 文件。
- [ ] Phoenix Gateway 使用 correlationId 查唯一根 Trace，并等待 `response.dispatch`；查询超时与 Agent 超时分开记录。
- [ ] 将 ResultEnvelope、before/after snapshot 和 Trace 一起交给 Normalizer/IntegrityChecker。
- [ ] 集成测试用 marker `integration`/`device` 隔离，默认 `pytest` 在无设备时跳过而不是失败。

**必须向用户确认的集成点**

当 TestApp 结果存储实现可用后，执行者必须先汇报以下实测结果，再把默认策略写入示例配置：

1. 模拟器上 `run-as <package>` 是否可用。
2. 结果文件的正式相对路径和命名规则。
3. 临时文件原子替换后 ADB 读取是否可能读到半文件。
4. 清理由 TestApp 负责还是电脑端发显式 cleanup command。

这些信息未确认前，保留配置项和 Fixture，不盲目选定目录。

### Task 3.4：完成一个真实最小闭环并扩展核心 Dataset

**Files**

- Modify: `datasets/demo_text_v1.jsonl`
- Create: `tests/integration/test_minimal_real_loop.py`
- Update: `README.md`
- Generate: `runs/local/<runId>/...`

**最小闭环顺序**

- [ ] 首先只启用一个低风险、单请求 Case，例如 `set_ac_status`。
- [ ] 运行 doctor，确认单模拟器、ADB reverse、Phoenix 项目和 TestApp Debug Bridge 可用。
- [ ] 电脑端生成 correlationId 和 command.json。
- [ ] 用户在 TestApp 半自动确认发送。
- [ ] 电脑端读取 TERMINAL AgentResponse、before/after snapshot。
- [ ] 电脑端按 correlationId 找到唯一 `agent.request` Trace。
- [ ] Normalizer 得到 ToolCall、Dispatch、状态变化和最终响应。
- [ ] 至少一个 ResponseGrader、ToolGrader、StateGrader 和 Hard Gate 执行。
- [ ] 生成完整 `manifest.json/results.json/failures.json/report.md`。
- [ ] 人工核对 Phoenix UI 与报告 TraceId、Tool 和终态一致。

最小闭环成功后再依次启用：普通聊天、参数工具、Safety deny、二次确认、取消/超时、Memory 隔离。每启用一类，都先跑 1 Trial 并确认协议字段真实存在。

**真实验收判定**

- 没有 AgentResponse：`INFRA_ERROR` 或 Bridge 错误，不评分回复。
- 有 AgentResponse 但无完整 Trace：`TRACE_INCOMPLETE`，不伪造轨迹 PASS。
- AgentResponse、Trace、状态冲突：Case FAIL 或 UNSCORABLE，并明确列出冲突。
- 只有 Logcat 证据：不算正式闭环完成。

### Phase 3 完成门

自动检查：

```powershell
uv run ruff check .
uv run pytest -q
uv run pytest -m integration -q
uv run aiagent-eval doctor --config <local-config>
uv run aiagent-eval run --executor manual --dataset datasets/demo_text_v1.jsonl --case <low-risk-case> --trials 1
```

人工检查：

- TestApp 显示的输入与 command.json 一致。
- AgentResponse.requestId 由 TestApp/AIAgent 链路产生，电脑端没有预造。
- AgentResponse.clientMessageId、ResultEnvelope.correlationId、Trace `client_message.id` 相同。
- Phoenix 只有一个匹配根 Trace，且包含 `response.dispatch`。
- 报告中的 Tool/Safety/状态与 Phoenix 和快照一致。
- 失败时产物仍可用于定位，且没有泄露密钥或完整 Raw Trace。

完成标准：至少一个真实低风险 Case 完成半自动闭环；若 Android 外部能力未就绪，只能标记“Phase 3 电脑端准备完成”。

## Phase 4：ADB 自动触发、配置化 Judge、Phoenix Dataset 与正式基线

### Task 4.1：实现 ADB 自动触发执行器

**Files**

- Modify: `src/aiagent_eval/executor/adb.py`
- Modify: `src/aiagent_eval/runner/run_manager.py`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_adb_executor.py`
- Test: `tests/integration/test_adb_auto_run.py`

**工作步骤**

- [ ] 自动执行器复用与 ManualExecutor 完全相同的 `EvalCommandEnvelope` 和 ResultSource。
- [ ] 使用显式 Component 发送命令，不使用 UIAutomator、坐标点击或文本识别。
- [ ] 与 TestApp 计划使用同一触发协议：JSON 以 UTF-8 编码后转成 URL-safe/no-wrap Base64，通过 `--es eval_command_b64 <BASE64>` 传递，并通过 `--ez eval_auto_execute true` 开启自动执行。
- [ ] 使用参数数组执行等价于 `adb -s <serial> shell am start -n com.hirain.aiagent.test/com.hirain.aiagent.test.eval.ui.EvalBridgeActivity --es eval_command_b64 <BASE64> --ez eval_auto_execute true` 的命令，不把 JSON 或用户文本拼入 shell。
- [ ] 自动模式限制原始 command JSON 最大 16 KiB，超过时返回明确配置/协议错误并提示改用 manual；Demo 不临时发明文件传输第二通道。
- [ ] ADB 触发只替换“用户在 TestApp 点击确认”这一步，Run、Trace、Grader 和报告不分叉。
- [ ] 命令接收确认、Bridge RUNNING、Agent TERMINAL、Trace 完整分别设超时。
- [ ] 自动执行失败可由用户重新以 `--executor manual --resume` 接管当前 Run。
- [ ] 首版仍保持单设备串行，不实现 worker pool。

### Task 4.2：实现可配置 Phoenix Evals Judge

**Files**

- Create: `src/aiagent_eval/judge/__init__.py`
- Create: `src/aiagent_eval/judge/phoenix_judge.py`
- Create: `rubrics/response_quality_v1.json`
- Modify: `src/aiagent_eval/graders/suite.py`
- Test: `tests/unit/test_judge.py`
- Test: `tests/integration/test_judge_provider.py`

**公共接口**

```text
Judge.evaluate(judge_input, rubric) -> JudgeResult
```

**工作步骤**

- [ ] 定义 Judge 抽象，业务层只认识 JudgeResult，不直接认识 DeepSeek/OpenAI 客户端。
- [ ] 使用 Phoenix Evals v3 的 provider/model 适配能力；Provider、model、baseUrl、key env、timeout、retry 全部来自配置。
- [ ] 通过 `phoenix.evals.llm.LLM` 的 OpenAI client 适配 OpenAI-compatible endpoint；baseUrl/apiKey 属于 client 配置，temperature/maxTokens/thinkingMode 属于 Judge Profile/调用配置，禁止混入评分业务分支。
- [ ] 默认 Profile 可配置为 DeepSeek OpenAI-compatible endpoint `https://api.deepseek.com` 和 `deepseek-v4-flash`，但任何代码分支不得判断该固定模型名；Demo 默认 temperature=0、thinkingMode=disabled 以降低 Judge 波动，实际参数和模型名写入 Run manifest。
- [ ] DeepSeek Profile 映射规则固定：LLM client 使用 provider=openai、client=openai、base_url/api_key；Judge 调用参数使用 temperature/max_tokens，并将 thinkingMode=disabled 映射为 OpenAI-compatible `extra_body={"thinking":{"type":"disabled"}}`。其他 Provider 不支持 thinking 参数时应显式省略，而不是发送 DeepSeek 私有字段。
- [ ] Judge 输入仅包含用户问题、最终 AgentResponse、参考期望、Tool/Safety/状态事实摘要和 Persona；不发送完整 Memory 或 Raw Trace。
- [ ] Rubric v1 只评估清楚自然、Persona、一致性和 Safety 解释，不评估 Tool 是否真实执行。
- [ ] Judge 使用结构化标签/分数/解释；输出必须通过 Pydantic 校验。
- [ ] 将 Phoenix Evals v3 返回的 Score/分类结果显式映射为本项目 JudgeResult，不依赖 legacy EvaluationResult 类型。
- [ ] Judge 默认在确定性事实可评分后执行；Hard Gate 已失败时可跳过以节省成本。
- [ ] Judge 网络失败、限流、非法输出分别记录 `JUDGE_ERROR`；Judge disabled/无凭证记录 `JUDGE_SKIPPED`。
- [ ] Judge 错误不把确定性 PASS 自动变成 Agent FAIL，但正式报告必须显示软评分缺失。
- [ ] 用少量人工标注 Fixture 校准 Rubric，不要求本期达到统计学指标。

### Task 4.3：实现 Phoenix Dataset 同步

**Files**

- Create: `src/aiagent_eval/datasets/phoenix_sync.py`
- Modify: `src/aiagent_eval/cli.py`
- Test: `tests/unit/test_phoenix_dataset_sync.py`
- Test: `tests/integration/test_phoenix_dataset_sync.py`

**工作步骤**

- [ ] 实现显式命令 `aiagent-eval dataset sync-phoenix <jsonl> --name <name>`，不在普通 validate/run 时自动同步。
- [ ] JSONL 始终为权威；同步时将输入、期望和 metadata 转换为 Phoenix Dataset 结构。
- [ ] 保存本地 Dataset hash、Phoenix dataset id 和同步时间到 Run manifest 或独立映射文件。
- [ ] 同名同 hash 时幂等返回；同名不同 hash 时默认拒绝覆盖，要求显式新版本名。
- [ ] 不把 Android 执行器塞入 Phoenix Experiment；同步仅用于数据管理和 UI 查看。
- [ ] Phoenix 不可用时不影响本地 Dataset 校验和 Fixture Eval。

### Task 4.4：正式 Run、基线对比和项目收尾

**Files**

- Modify: `README.md`
- Modify: `pyproject.toml`（仅补充已实际使用的工具配置）
- Create/Modify: `docs/protocol.md`
- Create/Modify: `docs/dataset-authoring.md`
- Create/Modify: `docs/runbook.md`
- Generate: `runs/baseline/<runId>/...`
- Test: 全量测试

**工作步骤**

- [ ] README 写明当前实际完成能力、快速开始、配置、半自动/自动模式、常见错误、隐私边界和未完成范围。
- [ ] `docs/protocol.md` 说明三方字段、ID 所有权、协议版本升级和兼容策略。
- [ ] `docs/dataset-authoring.md` 说明 Case/Scenario、Expectation、Hard Gate、点路径和首批工具名。
- [ ] `docs/runbook.md` 说明 Phoenix、ADB reverse、TestApp、doctor、运行、resume、报告和故障排查。
- [ ] 执行全部单元、契约、集成和至少一组真实核心 Dataset。
- [ ] baseline Run 使用 3 Trials；开发排障仍使用 1 Trial。
- [ ] baseline/release 目录只保存标准化产物，不保存 Raw Span、Logcat、临时命令、API 响应或凭证。
- [ ] 与上一个 baseline 比较，报告新增失败、恢复通过和简单性能变化。
- [ ] 对无法稳定通过的真实模型 Case，不通过放宽 Hard Gate 掩盖；记录为已知波动或修正 Dataset 期望并说明原因。
- [ ] 运行 `git status`，只准备本项目文件和正式 baseline/release 产物；由用户决定提交。

### Phase 4 完成门

```powershell
uv sync --locked
uv run ruff check .
uv run pytest -q
uv build
uv run aiagent-eval schema check
uv run aiagent-eval dataset validate datasets/demo_text_v1.jsonl
uv run aiagent-eval doctor --config <local-config>
uv run aiagent-eval run --executor adb --dataset datasets/demo_text_v1.jsonl --run-kind baseline
uv run aiagent-eval compare --baseline <previous-run> --candidate <current-run>
```

若 Judge 开启，额外验证 Judge Profile、Rubric 版本、错误降级和报告字段；若 Judge 未配置凭证，确定性 baseline 仍可完成，但报告必须标记 `JUDGE_SKIPPED`。

## 6. CLI 最终命令面

本期结束时只保留下列必要命令：

```text
aiagent-eval --help
aiagent-eval doctor --config <path>
aiagent-eval schema export
aiagent-eval schema check
aiagent-eval dataset validate <jsonl>
aiagent-eval dataset sync-phoenix <jsonl> --name <name>
aiagent-eval run --dataset <jsonl> --executor fixture|manual|adb [filters] [--fixture-bundle <path>]
aiagent-eval report --run <run-dir>
aiagent-eval compare --baseline <run-dir> --candidate <run-dir>
```

不增加 serve、dashboard、worker、queue、watch 等本期没有真实需求的命令。

## 7. 测试分层与验证策略

### 7.1 默认单元测试

- 不需要网络、ADB、Phoenix 或 Judge Key。
- 覆盖配置、协议、Schema、Dataset、ID、Normalizer、Integrity、Graders、Hard Gates、Runner、ArtifactStore 和 Report。
- 使用最小 Fixture，确保测试稳定快速。

### 7.2 契约测试

- 验证 Pydantic 模型、生成 JSON Schema 和 Android 样例 JSON 一致。
- Android 两计划实现后，将它们产生的真实 command/result/snapshot 样例复制为脱敏 Fixture。
- 协议 major 不兼容时明确失败；minor 新字段在兼容策略允许时处理。

### 7.3 Phoenix 集成测试

- 只有配置 Phoenix 时运行。
- 检查 Client 连通、按 correlationId 找根 Trace、获取完整 traceId 和 Dataset 同步。
- 不在测试中清空或批量删除 Phoenix 数据。

### 7.4 Device 集成测试

- 只有明确 serial 和 Debug TestApp 时运行。
- 串行执行，不与人工聊天同时进行。
- 首先低风险 Tool，再执行 Safety/confirmation/cancel。
- 真实设备依赖项失败归为 InfraError，不改写为 Agent Fail。

### 7.5 阶段性人工验收

每个真实 Run 至少抽查一条 Phoenix Trace，核对：

- correlationId/clientMessageId。
- AgentResponse.requestId。
- 根 span 与 response.dispatch。
- Tool Call、Safety、Dispatch、Writeback。
- 状态前后快照和 environmentRevision。
- 报告证据与 Phoenix UI 一致。

## 8. 最终验收标准

### 8.1 功能验收

- [ ] JSONL Dataset 可校验，错误能定位到行号和字段。
- [ ] Schema 可生成、检查且被 Android 样例验证。
- [ ] 一个命令可启动 fixture/manual/adb 三种执行模式。
- [ ] 半自动模式至少完成一个真实低风险 Case。
- [ ] 自动模式只替换触发步骤，不分叉评分和报告。
- [ ] Phoenix Trace 可按 correlationId 唯一关联。
- [ ] 多轮、多 Tool 和二次确认可标准化。
- [ ] 五类确定性 Grader 和 Hard Gate 可运行。
- [ ] Judge 可配置、可关闭、失败可降级。
- [ ] JSON/Markdown 报告和 baseline compare 可运行。

### 8.2 正确性验收

- [ ] 电脑端不生成或指定 requestId。
- [ ] `gen_ai.output` 不被当成最终 AgentResponse。
- [ ] 缺失、冲突、基础设施错误不会被默认值伪装为 PASS。
- [ ] Hard Gate 不能被 Judge 或其他分项抵消。
- [ ] Trial 默认策略为 dev=1、baseline/release=3。
- [ ] Case 串行且环境修订号可发现污染。

### 8.3 工程验收

- [ ] `uv sync --locked`、Ruff 和默认 pytest 通过。
- [ ] 无 Key、无设备时默认测试仍可运行。
- [ ] 集成测试按 marker 和配置显式启用。
- [ ] 本地配置、Raw Trace、Logcat、临时结果和凭证未进入 Git。
- [ ] README 和 docs 只描述已经真实完成的能力。

## 9. 预期风险与处理

| 风险 | 处理方式 |
|---|---|
| Android 协议未完成 | Phase 1/2 用 Fixture；Phase 3 明确阻塞，不伪报完成 |
| TestApp 结果目录未确定 | ResultSource 配置化；模拟器实测后确定默认策略 |
| Phoenix 字段/DataFrame 变化 | Gateway 转 RawSpan，Normalizer 不直接依赖 SDK 对象 |
| Trace 异步到达 | 按 correlationId 轮询并等待 response.dispatch |
| 重复/陈旧结果 | correlationId、时间窗、终态和唯一性联合校验 |
| 模型非确定性 | dev 1 Trial，baseline/release 3 Trials，报告稳定通过率 |
| Judge 不稳定或不可用 | 可关闭；错误独立状态；不覆盖确定性结论 |
| 状态并发污染 | 单设备串行、Eval 环境租约、environmentRevision 校验 |
| 报告泄露敏感内容 | 只保存标准化证据摘要与 TraceId，不提交 Raw Trace |
| 设计膨胀 | 不新增 DB/UI/队列/并发/插件系统；新需求进入后续独立计划 |

## 10. 实施顺序总结

1. **Phase 1：** 先建立可安装工程、配置、协议、Schema 和 Dataset，得到稳定输入契约。
2. **Phase 2：** 用 Fixture 跑通 Trace 标准化、确定性评分、Hard Gate、Run 和报告，形成离线最小闭环。
3. **Phase 3：** 接入 TestApp 结构化结果与真实 Phoenix Trace，先完成一个半自动低风险 Case，再扩展场景。
4. **Phase 4：** 在闭环稳定后增加 ADB 自动触发、配置化 Judge、Phoenix Dataset 同步和正式 baseline。

该顺序确保自动化不会早于最小评估闭环，也避免 Android 外部依赖阻塞电脑端核心评分与报告开发。
