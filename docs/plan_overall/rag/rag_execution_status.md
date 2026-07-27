# 车辆知识 RAG 执行台账

> 维护规则：本台账是 `vehicle_agent_rag_goal_execution_schedule.md` 的恢复点记录。
> 每个 Goal 仅在验收证据真实存在时更新为 `COMPLETED`；外部输入或环境缺失必须明确记录为 `WAITING_INPUT` 或未执行项。

## 当前执行状态

| 字段 | 当前值 |
|---|---|
| 总体状态 | `IN_PROGRESS` |
| 当前 Goal | `RAG-G903`（真实资料扩展试运行、检索质量校准与正式候选前置条件核查） |
| 唯一下一 Goal | `RAG-G903`（V4 已完成 Android 测试资产安装与本地检索验收；等待端到端回答、质量阈值与正式发布门槛） |
| 调度依据 | `vehicle_agent_rag_goal_execution_schedule.md` |
| 上位设计 | `vehicle_agent_rag_design.md` |

## RAG Parent-Child / Eval V2 改造台账

> 本节由 `docs/plan_overall/eval/08-rag-parent-child-retrieval-eval-v2-implementation-plan.md` 管理。V2 与既有 `RAG-Gxxx` 调度并行记录，但执行顺序以 V2 计划的 Phase Gate 为准；任何 V2 产物均不得覆盖下列历史 TEST_ONLY 资产。

| 字段 | 当前值 |
|---|---|
| V2 总体状态 | `IN_PROGRESS` |
| 当前 Goal | `RAG-EV2-G320`（最终 DoD、台账、README 和报告一致性审计） |
| 最近完成 Goal | `RAG-EV2-G319` |
| 唯一下一 Goal | 无（08 计划在已批准“先跑通”例外范围内完成；正式质量扩展和发布审批另立后续 Goal） |
| 执行依据 | `docs/plan_overall/eval/08-rag-parent-child-retrieval-eval-v2-implementation-plan.md` |
| 发布状态 | 所有既有与后续 V2 Bundle 均为 `TEST_ONLY`；本计划不授权提升 `APPROVED` 或复制至 `app/src/main/assets`。 |

### RAG-EV2-G001：冻结 V2 改造基线（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 工作区快照 | 已发现并保留前序未提交改动：`AIAgentService.kt`、`RagIndexerCommand.java`、`OfflineHybridEvaluator.java`；另有 `EvaluateRerankCommand.java`、`RerankDiagnosticReportWriter.java`、`RerankManualReviewReportWriter.java` 及本 V2 计划文档为未跟踪项。未执行删除、回滚或覆盖。 |
| 前序改动归类 | `AIAgentService.kt` 调整 DashScope Rerank 端点；CLI 增加 `evaluate-rerank`；`OfflineHybridEvaluator` 增加 Child 级 Rerank 诊断。它们是旧 V1 Child 评测链路的诊断能力，可在 V2 中按新协议重构或复用，但不能被视为 V2 已完成实现。 |
| 编译核对 | 在 `tools/rag-indexer` 执行 `./gradlew.bat test --console=plain`：`BUILD SUCCESSFUL`，8 个任务（4 executed、4 up-to-date）。该验证未调用云端、未改写 Corpus 或 Bundle。 |
| 历史 Eval 资产 | 保留 `corpus/model_y_2026_refresh_trial/evaluation_pdf_html_diy_service_v4.json`，SHA-256：`74376E8E2CD05F68782C59BED26ADEFF7BCE0790FF63FE90D3795FED12B879CF`；旧 V1/V2/V3/V4 JSON 与 `work/evaluation-*.json` 均为历史诊断输入/输出。 |
| 历史人工审核资产 | 保留 `work/evaluation-pdf-html-diy-service-rerank-manual-review-v4.json`，SHA-256：`914C69612EE938C956990A98F08ED399F18166DFBA0887CE1C4A0BCF7A432DA7`；对应诊断 JSON SHA-256：`8E8A0B1A33BCF1C67E9A0D52B24AE757F6504268C932DB5D651AFB463D11F6ED`；HTML 审阅页及历史 parser/chunk 审阅页均不覆盖。 |
| 历史 V4 Bundle | 既有台账记录 `TEST_ONLY-model-y-2026-refresh-v4-expanded` 的 `data.mdb` SHA-256 为 `d132b8c9da49d1c41328a336d21db81f72e1e21daae2e0f854191e5f1959bb72`。本次冻结时本地 Corpus 下未发现该已发布 Bundle 文件，因此不伪造重新 Hash 结果；历史记录仅作为追溯依据。 |
| 风险与边界 | 旧 V4 的 20 条 Child ID 标注和旧 `Recall@K` 结论只保留为历史诊断，不能迁移为 V2 Ground Truth；V2 必须使用 Parent Evidence Set 重新人工标注。ObjectBox Entity/Meta Model 本 Goal 未修改。 |
| 下一 Goal | `RAG-EV2-G002`：先修订 `vehicle_agent_rag_design.md` 的冲突协议，禁止直接开始 Chunk 编码。 |

### RAG-EV2-G002：修订上位设计与跨端协议（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 修改文件 | `vehicle_agent_rag_design.md`；`vehicle_agent_offline_rag_plan.md`；`vehicle_agent_android_rag_plan.md`；`vehicle_agent_rag_goal_execution_schedule.md`。 |
| 固化的唯一链路 | `Query → Dense/BM25 Child → RRF → Child 去重 → Child Rerank → Child→Parent → Parent 去重 → 完整 Parent Evidence + retrievalConfidence`。Parent 不参与默认 Dense/BM25；Rerank 输入只包含 `sectionPath + Child 正文`。 |
| 关键协议 | Parent 150～1200 Token（硬约 2000），Child 160～320（硬约 512），默认 overlap=0；最终通常 2～3、最多 4 个完整 Parent，初始 Evidence 预算 5000 Token；未校准置信度为 `UNASSESSED`。 |
| Eval 协议 | 明确 Eval V2 以 Parent Evidence Set 覆盖为主指标；Child Recall 仅诊断；至少 50 条人工复核样本，DEV 校准、TEST 仅最终确认。 |
| 历史计划处理 | 三份旧实施/调度文档顶部已增加 V2 覆盖说明，保留历史而不删改既有 Goal。 |
| 验证 | 使用冲突关键字扫描确认不再存在“最终 5 个 Child”“命中后补相邻 Child”等旧最终语义；`git diff --check` 通过。未改业务代码、未运行云端操作。 |
| 下一 Goal | `RAG-EV2-G003`：先建立 CLI/Android 共享的版本化 TokenEstimator 与 Golden，之后才允许改 Chunk 实现。 |

### RAG-EV2-G003：建立跨端 TokenEstimator V2 契约（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 新增共享契约 | `rag-schema/.../contract/RagTokenEstimator.java` 与 `RagTokenEstimate.java`。算法版本固定为 `rag-token-estimator-v2`：CJK 逐字、连续 Latin/数字每四代码点、标点逐个、换行逐个、普通空白不计、其他可见符号逐个。它是可解释预算估算，不宣称等于云端 tokenizer。 |
| 兼容处理 | 离线 `TokenEstimator` 默认委托 V2；原 `VERSION=1` 语义以 `LEGACY_VERSION` 和 `estimateV1ForHistoricalBundle` 保留，只用于审计历史 Bundle。V2 的持久化版本字段由下一 Goal 写入 Chunk Config 和 Manifest，旧 V1 Bundle 不会被静默重解释。 |
| 接入范围 | 离线 Chunk/Entity 映射经既有适配器使用 V2；Android `EvidenceBudgetPolicy` 改为调用同一共享实现。本 Goal 未改变 Context 通用预算器、ObjectBox Entity 或 Meta Model。 |
| Golden | 新增 `rag-schema/test-vectors/token-estimator-v2.json`，覆盖空白、纯中文、中英数字、标点、标题/步骤换行、Warning 与符号；CLI 与 Android 分别新增同一 Golden 消费测试。 |
| 验证 | `tools/rag-indexer: ./gradlew.bat test --tests '*TokenEstimator*' --console=plain` 通过；根工程 `./gradlew.bat :app:testDebugUnitTest --tests 'com.hirain.aiagent.rag.contract.RagTokenEstimatorGoldenTest' --console=plain` 通过。Android 编译仅出现既有 Kapt language-version 回退和未识别 processor options 警告，无失败。 |
| 下一 Goal | `RAG-EV2-G004`：使 V2 Config/Manifest 显式记录 `tokenEstimatorVersion` 与 Parent/Child 参数，并为 Android 建立未知版本 fail-closed。 |

### RAG-EV2-G004：V2 Chunk 配置与 Manifest 兼容协议（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 配置协议 | `ChunkingConfig` 支持 V1 历史配置与 V2 显式参数；`ConfigLoader` 对 V2 要求所有 Parent/Child、overlap、TokenEstimator 与语义策略字段，拒绝未知版本/字段。新增 `schemas/rag-build-v2.schema.json`。 |
| Manifest 协议 | `ManifestBuilder` 新增 V2 构建入口；`knowledge-bundle-manifest.schema.json` 使用 V1/V2 `oneOf`；Android Parser 对 V2 完整参数、`rag-token-estimator-v2` 与参数范围 fail-closed，V1 Fixture 保持可读。 |
| 测试 | 新增离线 V2 Config 正例和 Android V2 Manifest 正反例。离线 `./gradlew.bat clean test check --console=plain` 通过；Android `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain` 通过。 |
| 边界 | 未改 ObjectBox Entity、Meta Model 或历史 Bundle；未构建真实 V2 Bundle；旧 V1 Manifest 仍由旧分支解析。 |
| 下一 Goal | `RAG-EV2-G101`：先扩展 PDF/HTML/Markdown 的结构事实，再开始 Section Tree 与语义分块。 |

### RAG-EV2-G101：扩展统一结构 Block 元数据（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 结构契约 | 新增 `BlockStructure`、`BlockRole`、`SequenceType`；`StructuredBlock` 增加结构字段并保留旧构造兼容。结构包含 heading level、sectionPath、列表/步骤组、深度、不可切断标记与稳定 ordinal。 |
| HTML/Markdown | HTML 输出 h1～h6、DOM 列表组与层级；Markdown 输出 Heading、ListItem、GFM 既有 Table、列表组与嵌套深度。列表项被标为原子语义组。 |
| PDF | 新增保守 `PdfHeadingLevelResolver` 与 `PdfHeadingPathTracker`。明确编号、中文编号或显著字号才产生层级；仅标题候选而无可靠层级时输出 `PDF_HEADING_LEVEL_UNRESOLVED` 诊断，不伪造路径。Warning 继承 sectionPath 并标记原子组。 |
| 验证 | HTML、Markdown、PDF Parser 定向测试通过；新增 Markdown/HTML 列表结构和 PDF 层级断言。ObjectBox Schema 未修改。 |
| 下一 Goal | `RAG-EV2-G102`：依据 BlockStructure 构建 Section Tree、最小自然小节 Parent 与超长语义 Parent 拆分。 |

### RAG-EV2-G102：构建 Section Tree 与小节级 Parent（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 实现 | 新增 `DocumentSection`、`DocumentSectionTreeBuilder`、`SectionParentChunker`；V2 仅在 Parser 已提供标题层级时启用。大章节仅保留路径，直接正文按最小自然小节生成 Parent，兄弟小节不合并。 |
| 边界 | Parent 拼接后的实际文本（包含换行）统一按 `rag-token-estimator-v2` 计数；超过 2000 的不可拆单元输出 `PARENT_ATOMIC_UNIT_OVER_HARD_LIMIT`，不静默截断。V1 `HeadingAwareParentChunker` 保持兼容。 |
| 验证 | `DocumentChunkerTest` 覆盖最小小节、兄弟小节不合并及 V2 真实拼接 token 边界；最新真实资料审核运行中 Parent=910、`parentOverHard=0`。 |
| 下一 Goal | `RAG-EV2-G103`：在 Parent 内生成语义完整的 Child。 |

### RAG-EV2-G103：实现 Parent 内语义 Child 与兜底 overlap（2026-07-24，COMPLETED）

| 项目 | 记录 |
|---|---|
| 实现 | 新增 `SemanticChildSplitter`；V2 分别传入 target=256、soft=384、hard=512。普通段落按 soft 上限聚合，完整列表、步骤、Warning 作为原子组保留；默认 overlap=0。 |
| 长度兜底 | 仅单个超长普通 Block 进入句子级 `LENGTH_FALLBACK`；完整句子才可复用为 5%～10% 边界 overlap。无句界文本按 Unicode 码点安全切分、overlap 固定为 0，并输出 `CHILD_LENGTH_FALLBACK_WITHOUT_SENTENCE_BOUNDARY`。 |
| 可复现性 | HTML 列表组不再使用 JVM 对象地址，改为 DOM 遍历序号 `html-list-N`；重复解析测试验证组 ID 稳定。 |
| 验证 | `DocumentChunkerTest`、`StaticHtmlDocumentParserTest` 定向通过；真实资料运行中 Child=1261，`childOverHard=7`，均对应可审计 `CHILD_ATOMIC_GROUP_OVER_HARD_LIMIT`。 |
| 下一 Goal | `RAG-EV2-G104`：生成质量报告并完成 Phase 1 本地人工审核。 |

### RAG-EV2-G104：Chunk 质量分析与本地审核页（2026-07-24，IN_PROGRESS）

| 项目 | 记录 |
|---|---|
| 报告能力 | 已新增 `ChunkQualityAnalyzer/Report/Writer` 与 `ChunkQualityFinding`，并扩展 `chunk-review.html` 展示 Parent 正文、Child 的 Parent 序号、index、token、splitReason、overlap、格式与来源位置。报告统计 Parent/Child 分布、超软/硬上限、重复内容 Hash、每 Parent Child 数、overlap 与原子超长项；每条硬上限发现还包含文档、Parent/Child 序号和 `SourceLocator`，不再只有汇总原因码。 |
| 最近运行 | 本地 `validate` 成功：`runId=c2553a9d-e0da-4a34-bcd3-7e9e57357973`，目录为 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/runs/c2553a9d-e0da-4a34-bcd3-7e9e57357973/`。未请求 Embedding、Rerank 或其他云端服务。 |
| Warning 修复 | 复核最大 1634-token 项后确认其为目录条目中包含“警告”的误判；`PdfWarningDetector` 改为仅接受行首显式警示标签，并且只在警示句尚未结束时补入紧随续行，避免吞并同页后续章节。对应 PDF 回归测试通过。 |
| 最近质量 | Parent=908，Child=1270，Parent 超硬上限=0，Child 超硬上限=4；4 项均为 DIY 完整操作步骤，仍记录 `CHILD_ATOMIC_GROUP_OVER_HARD_LIMIT`，须由人工确认保留或授权拆分。出现 9 组 exact Child Hash 重复，均可回溯到 DIY 目录中不同 Parent 的相同操作文本；不修改原始资料，后续由 Phase 2 RRF 后候选去重处理。 |
| 自动 Gate | `tools/rag-indexer: .\\gradlew.bat clean test check installDist --console=plain` 已通过（12 tasks）；`ChunkQualityAnalyzerTest`、`PdfWarningDetectorTest` 定向通过。 |
| 未完成 Gate | 计划要求项目负责人审阅当前 `parser-review.html` 与 `chunk-review.html`，抽检 PDF、保修 HTML、DIY、服务中心、短小节、长步骤、Warning、表格和多 Child Parent，并明确接受或要求调整后，才允许把本 Goal 标记完成并进入 `RAG-EV2-G201`。 |

### G104 增量：原子语义组安全拆分与真实语料复核（2026-07-24）

| 项目 | 结果 |
|---|---|
| 改造原因 | 初版 `SemanticChildSplitter` 对列表、步骤和 Warning 原子组超过 Child 硬上限时只记录诊断，仍会生成超大 Child；这不符合“原子组可以在安全语义边界拆分”的已确认规则。 |
| 实现 | `SemanticChildSplitter` 现在对超大原子组按“完整句子 → `；`/`：`/`,` 等次级边界 → Unicode 长度兜底”递归拆分；同一 Parent 内不产生 overlap，保留 `WARNING` evidenceType 和 `ATOMIC_GROUP_FALLBACK` splitReason；无可用边界时输出 `CHILD_ATOMIC_GROUP_SPLIT_FALLBACK` 诊断。修复语义打包时重复追加当前缓冲区导致 Child 膨胀的问题。 |
| 测试 | `DocumentChunkerTest` 9 项通过；Chunk 包全部 17 项通过（含 TokenEstimator、Embedding 输入、稳定 ID、表格和来源定位测试）。由于本机 Gradle Native Platform/Wrapper 锁异常，使用已缓存 JUnit 5.12.2 Launcher 进行等价离线执行，未调用网络。 |
| 真实资料验证 | 使用新编译的 V2 Chunker 对 Model Y 全量 Corpus 执行 `RagIndexerMain validate`，结果 `VALIDATION_PASSED`，runId=`541b13f3-a8f9-496b-ad51-054e214fecd0`。Parent=908、Child=1654、ParentOverHard=0、ChildOverHard=0、AtomicOverHard=0、ChildOverSoft=13、ParentAsChild=627、FallbackOverlap=0。 |
| 审核产物 | `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/runs/541b13f3-a8f9-496b-ad51-054e214fecd0/chunk-review.html` 与 `chunk-quality.json`。该运行只做解析/分块/质量验证，没有请求 Embedding、Rerank，也没有覆盖历史 Bundle。 |
| 当前结论 | 自动质量 Gate 已明显改善，但 `ChildOverSoft=13` 和 `ParentOverSoft=51` 仍是“软阈值诊断”，不等于失败；必须由项目负责人抽检完整 Parent、Child 起止句、列表/Warning 原子组和 PDF/HTML 来源后，才能关闭 G104。人工审核未完成前不得进入 `RAG-EV2-G201`，不得重建正式 Embedding 或提升 `APPROVED`。 |

### G104 修订计划 09：PDF 行级读取、Paragraph Reconstruction 与 Embedding Child（2026-07-25，自动验证完成，等待人工 Gate）

| 项目 | 结果 |
|---|---|
| PDF 两列读取 | `PdfBox` 文本回调按来源文本组保留，页面阅读顺序固定为左列自上而下、再右列自上而下；每个视觉行保留页码、列序、行序、BoundingBox 和 `fullWidth`。PDF 不再在 Parser 阶段按句子合并。 |
| Parent/Paragraph | Parent 先按自然小节生成，再在 Parent 内按列、行距、水平边界、页连续性和句末状态恢复 Paragraph；Warning、列表、标题、表格和列切换均为边界。超硬 Parent 才按完整 Paragraph 拆分，并记录约 10% overlap。 |
| Child | PDF Parent 使用 Paragraph 作为基本单位；160～320 token Paragraph 独立，320～384 也独立，只有短 Paragraph 在向量 cosine 严格 `>0.7` 且不超过 384 时合并；单 Paragraph 超 512 才进入句子/次级标点/长度兜底。 |
| 代码与协议 | 已完成 A1、B1-B3、C1-C3、D1-D3；新增分块策略版本、列布局元数据、Paragraph 中间模型、Paragraph Embedding 缓存适配、Child Planner 和质量统计字段。正式构建的 Embedding 失败会 fail-closed；`validate` 仅允许显式 fallback。 |
| 自动化测试 | 直接 JDK 编译主源码通过；从 `tools/rag-indexer` 目录运行 153 个 JUnit 测试，153/153 通过。 |
| Model Y 全量验证 | `VALIDATION_PASSED`，runId=`e2979946-751a-4cf0-8c13-f7c040e2b373`；Parent=956、Child=3821、Paragraph=5391、ParentOverHard=0、ChildOverHard=0、ParentOverlap=20、SemanticMerge=2354。 |
| 审核产物 | `tools/rag-indexer/build/final-v2-validate/runs/e2979946-751a-4cf0-8c13-f7c040e2b373/parser-review.html`、`chunk-review.html`、`chunk-quality.json`。 |
| 当前结论 | 自动化阶段完成，但人工审核尚未完成。请重点审阅两列顺序、Paragraph 边界、Child 是否以完整段落/主题为单位、Warning/列表原子性及 Parent overlap；审核通过前不得执行旧计划 Phase 2、重新构建正式 Bundle 或提升 `APPROVED`。 |

## Goal 记录

| Schedule Goal | Source Task | Status | Started / Finished | Changed Files | Verification | Artifacts | Decisions | Risks / Next |
|---|---|---|---|---|---|---|---|---|
| RAG-G000 | 调度专用：基线与执行台账 | COMPLETED | 2026-07-21 / 2026-07-21 | `docs/plan_overall/rag/rag_execution_status.md` | `./gradlew.bat testDebugUnitTest --console=plain`：通过；24 个任务，退出码 0 | 本台账 | 不读取、不写入外部 `AIAgent_RAG` 目录；不修改凭证或本地配置 | 已进入 RAG-G001 |
| RAG-G001 | 离线 Task 0.1：独立 Java 17 CLI 工程骨架 | COMPLETED | 2026-07-21 / 2026-07-21 | `.gitignore`；`tools/rag-indexer/**` | `tools/rag-indexer/gradlew.bat test installDist`、`distZip`：通过；分发脚本 `--help`、`--version`：通过；根 `gradlew.bat projects`：仅 `:app` | `tools/rag-indexer/build/distributions/rag-indexer-0.1.0-SNAPSHOT.zip`（本地忽略产物） | CLI Toolchain 实测 Microsoft JDK 17.0.17；Wrapper 固定 Gradle 8.11.1 并沿用仓库既有镜像 | 进入 RAG-G002；未引入 ObjectBox、Parser 或其他正式依赖 |
| RAG-G002 | 离线 Task 0.2：依赖、许可证与安全审查门禁 | COMPLETED | 2026-07-21 / 2026-07-21 | `tools/rag-indexer` Version Catalog/Build/Test；共同 Gate 与依赖报告 | `clean test check installDist distZip`：通过；同 JVM PDFBox/Tabula、静态 HTML、CommonMark/GFM Table Smoke Test：通过；运行时依赖树：已解析 | `rag_dependency_compatibility_gate.md`；`tools/rag-indexer/dependency-license-report/runtime-dependency-resolution.md` | 项目负责人已批准 ObjectBox 的 APK/AAB、离线 CLI、预构建 Bundle、Vector Search 与目标 ABI 使用范围；Tabula 图像组件已显式排除 | 进入 RAG-G003；组织认可的漏洞扫描工具当前未配置，已如实记录为未执行，不影响后续技术 Gate 但必须在可用环境补扫 |
| RAG-G003 | 离线 Task 0.3：共享 ObjectBox Schema 唯一规范源 | COMPLETED | 2026-07-21 / 2026-07-21 | `rag-schema/**`；`tools/rag-indexer/build.gradle.kts`；Schema 契约测试 | `clean test check installDist distZip`：通过；外部 `sourceSets` 直连共享 Entity；四 Entity、1024/COSINE HNSW、Meta Model Fingerprint 与全部 Golden JSON 验证通过 | `rag-schema/objectbox-models/default.json`；Manifest Schema；四组 Golden；Schema README | CLI ObjectBox Processor 直写共享 Meta Model，首次生成 UID 后构建前强制要求文件存在，禁止删除自动再生 | 进入 RAG-G004；Android 端尚未消费该 Schema，跨端打开与目标 ABI 验证未完成 |
| RAG-G004 | 离线 Task 0.4-A：最小 ObjectBox Fixture 生产端 | COMPLETED | 2026-07-21 / 2026-07-21 | `ObjectBoxCrossRuntimeSpikeTest`；Fixture Gradle 任务；`rag-schema/test-fixtures/objectbox-v1/**` | `clean test generateObjectBoxFixture`：通过；离线 Store 写入并重开验证 3 文档、3 Parent、3 Child、1024 维 Child 向量和 1 条 posting；G008 已完成跨端消费收口 | `data.mdb`（SHA-256 已写入 Manifest）；`manifest.json`；`fixture-report.json` | Fixture 为受控合成数据，`publishable=false` 仅记录于报告；生成任务拒绝覆盖非空 Fixture；最终 Manifest 具备 V1 必填协议字段 | 离线 Task 0.4 已由 G008 联合收口；进入离线 Phase 1 |
| RAG-G005 | Android Task 0.1：依赖与发布审查补充 | COMPLETED | 2026-07-21 / 2026-07-21 | 共同兼容 Gate 报告 | Android 基线、ObjectBox 5.4.0 一致性、Parser 不入 APK、候选 ABI 与设备缺失状态均已记录 | `rag_dependency_compatibility_gate.md` Android 补充章节 | 初始目标 ABI 为 `arm64-v8a`；真实设备验证未伪造为通过 | 进入 G006 |
| RAG-G006 | Android Task 0.2：共享 Schema/Fixture 消费与最小打开验证 | COMPLETED（等效 Android 运行时） | 2026-07-21 / 2026-07-21 | Version Catalog、根/App Gradle、`ObjectBoxFixtureInstrumentedTest` | `connectedDebugAndroidTest --console=plain`：通过，Automotive API 35 `x86_64` AVD 共执行 2 个测试；RAG 测试实际打开 CLI Fixture，并通过 Dense、postings、Scope/Metadata 查询 | Android `MyObjectBox` 生成代码；生成测试 Asset；未压缩 Fixture 测试 APK；connected test report | Android 直接消费共享源码与 Meta Model；Fixture 仅打入 androidTest APK；测试读取 Instrumentation Context assets、在目标应用私有目录打开 DB | `x86_64` 仅为等效 Android 运行时，不能替代目标 `arm64-v8a` 的原生库与车机验证；该限制移交 G008 |
| RAG-G007 | Android Task 0.3：Android 构建与运行兼容策略 | COMPLETED | 2026-07-21 / 2026-07-21 | `app/build.gradle.kts`；共同 Gate；Instrumentation 测试 | `testDebugUnitTest`、`assembleDebug`、`connectedDebugAndroidTest` 全部通过；已授权 Automotive API 35 `x86_64` AVD 作为详细计划允许的等效运行环境；Debug APK 含 `lib/arm64-v8a/libobjectbox-jni.so`（2,553,248 字节） | Debug APK、AndroidTest APK、Gradle connected report | 保持未过滤多 ABI 打包；测试 APK 中 Fixture 长度和压缩后长度同为 106,496 字节，确认 `noCompress` 生效 | 最终正式发布仍应在实际目标 ABI 上复测，但不阻断已授权的 Phase 0 等效环境 Gate |
| RAG-G008 | 离线 0.4-B + 共同 Gate | COMPLETED | 2026-07-21 / 2026-07-21 | 共同 Schema/Fixture、离线 CLI、Android Build/Instrumentation、兼容报告 | CLI `clean test check installDist distZip` 通过；Android `testDebugUnitTest assembleDebug connectedDebugAndroidTest` 通过；Schema SHA-256 与提交指纹一致；同一 Fixture 已在 Android 真实 ObjectBox Runtime 打开并验证 Dense、Lexical、Scope 正反例及 PDF/HTML/Markdown Locator | `rag_dependency_compatibility_gate.md`；Fixture Manifest/Report；APK/AndroidTest APK；connected report | 依据 Android 详细计划的“目标 ABI 或等效环境”条款，已授权 Automotive `x86_64` AVD 是本 Phase 的等效验证环境；未将 Fixture 伪装为正式 Bundle | 共同 Phase 0 关闭；严格进入 `RAG-G101`，不得并行提前实施 Android Phase 1 |
| RAG-G101 | 离线 Task 1.1：CLI 命令、退出码与运行上下文 | COMPLETED | 2026-07-21 / 2026-07-21 | `cli/**`；`pipeline/BuildExecutionContext`；`BuildCancellationToken`；CLI 测试 | `test smokeTest installDist` 通过；安装分发 `--version` 输出 Manifest 的 `0.1.0-SNAPSHOT`；合法 `validate` 建立运行上下文后返回受控 `PIPELINE_NOT_AVAILABLE` / exit 7 | 安装分发脚本；CLI/退出码/取消 JUnit 测试 | G101 仅建立入口边界，不提前加载 Parser、网络或 ObjectBox Pipeline；路径只在内存归一化，默认输出不含绝对路径 | 进入 G102：定义并严格校验 Corpus/Build Config Schema、Hash 与 Scope |
| RAG-G102 | 离线 Task 1.2：Corpus/Build Config Schema、Hash 与 Scope | COMPLETED | 2026-07-21 / 2026-07-21 | `schemas/**`、`examples/**`、`config/**`、`corpus/**`、`validate` 命令与定向测试 | `clean test check installDist` 通过；Scope Golden、重复 ID、未知字段、跨 Scope、HTML 安全边界、配置指纹顺序无关均有测试 | Corpus/Build Schema 与 TEST_ONLY 示例；严格 Loader/Validator；确定性 SHA-256 指纹 | 公开协议使用总体设计/Manifest 的既有命名；未知字段硬失败；`validate` 不读取正文、不调用网络或写 Store | 进入 G103：安全路径、符号链接、格式探测、Charset 与资源限制 |
| RAG-G103 | 离线 Task 1.3：安全输入解析与格式探测 | COMPLETED | 2026-07-21 / 2026-07-21 | `CorpusPathResolver`、`SourceFormatDetector`、`SourceCharsetResolver`、`CorpusSecurityValidator`、`Sha256` 与定向测试 | `clean test check installDist` 通过；路径逃逸、格式伪装、未知 Charset、文件超限和 Hash 不匹配均映射受控失败 | 流式 SHA-256；Root 真实路径解析；资源预算门禁 | 默认 UTF-8，禁止机器默认编码；文件必须在真实 Corpus Root 内且是普通文件 | 进入 G104：统一 DTO、Locator、诊断与 Parser Registry |
| RAG-G104 | 离线 Task 1.4：统一领域模型、诊断与 Parser Registry | COMPLETED | 2026-07-21 / 2026-07-21 | `model/**`、`parser/DocumentParser`、`DocumentParserRegistry` 与测试 | `DocumentParserRegistryTest` 通过；精确格式路由、未注册格式和重复注册均被拒绝 | 统一 SourceFormat、SourceLocator、无堆栈诊断与 Registry | Parser 层不承担 Chunk、Embedding、网络或 Store 写入；只接受安全校验后的格式枚举 | 进入 G201：PDF 文本分类、逐页提取和阅读顺序 |
| RAG-G201 | 离线 Task 2.1：PDF 分类、字形、阅读顺序与安全诊断 | COMPLETED | 2026-07-21 / 2026-07-21 | `parser/pdf/**`；统一 Parse DTO；PDF 组件测试 | `gradlew.bat test --tests "*Pdf*Test" --console=plain`：通过；覆盖文本、MIXED、需密码/禁止内容提取的加密、允许只读提取的无密码加密、损坏 PDF、双栏顺序、标题、重复页眉页脚、页码标签和 JavaScript OpenAction 只诊断不执行 | `PdfDocumentParser` 输出带物理页、BoundingBox、Confidence 的统一 Block 与稳定诊断 | V1 始终拒绝未审核 MIXED；不 OCR、不执行或提取 PDF 主动内容；无密码且允许内容提取的 PDF 仅只读解析；标题为保守候选，复杂表格留给 G202 | 进入 G202；页眉页脚与标题阈值仍须在获准真实语料阶段校准 |
| RAG-G202 | 离线 Task 2.2：PDF 表格、跨页表格与 Warning | COMPLETED | 2026-07-21 / 2026-07-21 | `model/TableBlock`；`parser/table/**`；`parser/pdf/*Table*`、`PdfWarningDetector`；Build Config Schema | `gradlew.bat test check --console=plain`：通过；覆盖 Stream/Lattice 真实合成 PDF、逐页策略覆盖、列数校验、自包含渲染、跨页页码/表头/位置连续性、Warning 上下文聚合 | 统一 `TableBlock`（含策略、置信度、Locator、BoundingBox）；严格 PDF 策略配置与示例 | Tabula 无法证明的复杂嵌套/合并单元格不猜测，结构异常进入受控失败；不 OCR、不用颜色作为 Warning 唯一依据 | 进入 G203：静态 HTML 安全 Parser |
| RAG-G203 | 离线 Task 2.3：严格静态 HTML Parser 与资源边界 | COMPLETED | 2026-07-21 / 2026-07-21 | `parser/html/**`；HTML Config Loader/示例；HTML 定向测试 | `gradlew.bat test check --console=plain`：通过；覆盖本地 DOM 清洗、无主动资源、内容根唯一性、DOM 顺序/Heading Path/Locator、简单 DOM 表格、span 诊断、动态页面拒绝、噪声过滤和深度 Guard | `StaticHtmlDocumentParser`；安全策略、DOM Guard、配置 DTO/Loader | 不使用 URL Parser、网络、脚本或动态渲染；Cookie/站点特有噪声只经审核 Selector 过滤；复杂 rowspan/colspan 不猜测 | 进入 G204：CommonMark + GFM Table Markdown Parser |
| RAG-G204 | 离线 Task 2.4：CommonMark + GFM Table Markdown Parser | COMPLETED | 2026-07-21 / 2026-07-21 | `parser/markdown/**`；Markdown Config Loader/Parser；Markdown 定向测试 | `gradlew.bat test check --console=plain`：通过；覆盖 Front Matter 跳过且行号连续、标题/段落/代码顺序、原始 HTML 诊断、GFM Table→TableBlock 和配置闭环 | `MarkdownDocumentParser`；SourceSpan Locator；GFM Table 统一输出 | 仅支持 CommonMark + GFM Table；原始 HTML 不执行；未支持扩展在配置构造期拒绝 | 进入 G205：三格式统一质量/Locator 连续性与离线 Phase 2 Gate |
| RAG-G205 | 离线 Task 2.5：统一解析质量、Locator 连续性与 Phase 2 Gate | COMPLETED | 2026-07-21 / 2026-07-21 | `ParseQualityValidator`、`DocumentParsingPipeline` 与质量 Gate 测试 | `gradlew.bat test check --console=plain`：通过；覆盖 PDF/HTML/Markdown Locator 协议、表格列结构、错误诊断和集中 Pipeline 阻断 | 统一 `ParseQualityReport`；Registry 后解析 Gate | 未通过质量 Gate 的 ParseResult 禁止进入 Chunk/Embedding/Store；真实资料阈值仍留给 G903 校准 | 离线 Phase 2 关闭，进入 G301 Heading-aware Chunk |
| RAG-G301 | 离线 Task 3.1：Heading-aware Parent/Child Chunk | COMPLETED | 2026-07-21 / 2026-07-21 | `chunk/**`（模型、预算、Parent/Child、表格/Warning/Locator 策略）与测试 | `gradlew.bat test check --console=plain`：通过；覆盖 Heading Parent、Warning 原子性、Token 估算、连续 Locator 合并、长表自包含行拆分 | Parent/Child/ChunkResult；版本化 TokenEstimator | G301 不生成稳定 ID、Embedding 或 Store；预算仍为实现安全值，真实资料校准留给 G903 | 进入 G302：稳定 ID、Embedding 输入模板与可重复顺序 |
| RAG-G302 | 离线 Task 3.2：稳定 ID、Embedding 输入与可重复顺序 | COMPLETED | 2026-07-21 / 2026-07-21 | `StableIdGenerator`、`ChunkCanonicalizer`、`EmbeddingTextRenderer`、`ReproducibleOrdering`、`ReproducibleBuildClock` 与测试 | `gradlew.bat test check --console=plain`：通过；直接读取 `rag-schema/test-vectors/embedding-input-v1.json` Golden，验证稳定 SHA-256 ID、规范化和排序 | Embedding V1 模板与共享 Golden；SOURCE_DATE_EPOCH 时钟 | 尚未调用 DashScope 或生成向量；真实 Embedding 授权仍由 G303/G903 管理 | 进入 G303：批处理、缓存、重试、取消与向量硬校验 |
| RAG-G303 | 离线 Task 3.3：DashScope 文档 Embedding、批处理、重试、取消与缓存 | COMPLETED | 2026-07-21 / 2026-07-21 | `embedding/**`；缓存、协调器、DashScope 客户端及 Mock 测试 | `gradlew.bat test check --console=plain`：通过；真实 DashScope 合成文本调用返回 1 条 1024 维向量；Mock 覆盖无 Key 受控失败、乱序响应 index 回填、缓存恢复、取消、重试和有限向量校验 | DocumentEmbeddingClient、DashScope 客户端、缓存、协调器 | 生产 Key 仅经 `DASHSCOPE_API_KEY` 读取，不写入代码或日志；真实验证只发送合成文本 | 未获准上传正式资料；该限制不阻塞 G304 实现，正式语料构建仍由 G903 管理 | 进入 G304：词法索引与 Hybrid 检索构建 |
| RAG-G304 | 离线 Task 3.4：跨端一致 Analyzer 与 postings | COMPLETED | 2026-07-21 / 2026-07-21 | `lexical/**`、共享 `lexical-analyzer-v1.json` 与五组单测 | `gradlew.bat test check --console=plain`：通过；覆盖 CJK bi/tri-gram、NFKC 全半角折叠、英文/故障码/版本 Token、共享 Golden、Child-only tf/df/length/avgdl、posting 引用与稳定排序 | LexicalAnalyzer V1、LexicalIndex 中间模型、共享 Golden | Android 端尚未接入同一 Golden；G601 必须消费该文件并建立跨端门禁 | 未生成完整 Bundle，且正式语料/正式向量仍未获准，不得发布 | 进入 G401：领域模型到共享 ObjectBox Entity 的严格映射 |
| RAG-G401 | 离线 Task 4.1：领域模型到共享 ObjectBox Entity 的严格映射 | COMPLETED | 2026-07-21 / 2026-07-21 | `store/**` Mapper、StoreWriteModel、StoreModelValidator 与六组测试 | `gradlew.bat test check --console=plain`：通过；覆盖四类 Entity Mapper、PDF/HTML/Markdown Locator 互斥还原、排序 Metadata JSON、Parent/Child 向量约束及写库前 Document/Parent/Child/posting 交叉引用 | 严格 Corpus Metadata 映射、长度前缀 HeadingPath V1、写库前 Store 模型 | G402 必须把逻辑 posting 的稳定 Child ID 映射为本次 Store 的 ObjectBox long ID；不得把 long ID 暴露到外部协议 | 尚无 Store/Manifest/Bundle，不得对 Android 交付 | 进入 G402：确定性 Writer 与 Store 自检 |
| RAG-G402 | 离线 Task 4.2：确定性 ObjectBox Writer 与 Store 自检 | COMPLETED | 2026-07-21 / 2026-07-21 | `ObjectBoxKnowledgeStoreWriter`、Factory、DeterministicEntityWriter、Verifier、Statistics/Result 与四组测试 | `gradlew.bat test check --console=plain`：通过；合成 staging Store 覆盖 Document→Parent→Child→Term→Metadata 稳定写入、当前 Store Child long ID posting、关闭后重开、Dense/Term/Metadata 查询、计数/引用/向量校验及非空/损坏 Store 拒绝 | 仅 staging 写入；重开自检后才允许后续文件 Hash | G403/G404 负责 Manifest、报告、Hash 与原子发布；本阶段不复制或覆盖 Android Asset | 未生成开发 Bundle，不得对 Android 交付 | 进入 G403：稳定 Manifest、Store Metadata 与 Build Report |
| RAG-G403 | 离线 Task 4.3：稳定 Manifest、Store Metadata 与 Build Report | COMPLETED | 2026-07-21 / 2026-07-21 | `artifact/**`、`report/**`、共享 Schema 合同校验与测试 | `gradlew.bat test check --console=plain`：通过；覆盖关闭后数据文件 Hash、稳定 JSON、共享 Schema 顶层必填/未知字段、发布硬门禁、报告阶段摘要、同次关闭 Store→Manifest→重开正向一致性；历史不一致 Fixture 被受控拒绝 | Manifest、Build Report、发布资格与 Store 一致性校验器 | 当前 JSON Schema 校验覆盖顶层 required/额外字段；Android 端仍须在 G504 使用同一 Schema 做激活正反例 | G404 负责完整 Build/Verify/Pipeline/原子发布，正式语料仍由 G903 管理 | 进入 G404：完整 Pipeline、工作目录与原子输出 |
| RAG-G404 | 离线 Task 4.4：完整 Pipeline、工作目录与原子输出 | COMPLETED | 2026-07-21 / 2026-07-21 | `pipeline/DevelopmentBuildPipeline.java`、`BuildComponentFactory.java`、`ArtifactPublisher`、`VerifyPipeline`、`BuildCommand.java`、端到端测试与 `generateDevelopmentBundle` 任务 | `gradlew.bat test check`、`generateDevelopmentBundle` 均通过；实际 CLI `verify` 输出 `VERIFY_SUCCESS`；测试覆盖 TEST_ONLY 交接、数据篡改拒绝、APPROVED 原子发布和交付目录严格三文件 | 开发候选：`rag-schema/test-fixtures/development-v1/candidate-v1/`；`data.mdb` SHA-256=`c8cc09d2bb36e21d2e3f00b46ef7d11a7ba4b3cbe95290ca514b8e27b8fe07a9`，报告为 `publishable=false` | Checkpoint 指纹门禁、重复 runId 拒绝与 Embedding Cache 安全恢复均有测试；未定义的 Parser/Chunk 正文状态不做不安全反序列化恢复 | 开发 Bundle Gate 已关闭；严格进入 RAG-G501，正式资料与正式 Bundle 仍由 G903/G904 管理 |
| RAG-G501 | Android Task 1.1：跨层领域模型与序列化协议 | COMPLETED | 2026-07-21 / 2026-07-21 | `app/.../rag/model/**`、`rag/document/SourceLocatorEntityMapper.java`、`SourceCitationRenderer.java` 与三组单测 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.*' --console=plain`：通过 | 不可变 SourceLocator/Profile/Metadata、内部 RagResult/RetrievalEvidence、模型侧白名单 ToolResult、JSON Codec 与三格式 Citation | 内部检索分数/排名/ID/耗时与模型 DTO 采用不同类型；`answerable=false` 拒绝正文 Evidence；0 仅表示 Locator 不适用 | 进入 G502：VehicleStateMachine 可信 Profile 与 Scope Resolver；不得从请求/模型参数构造 Profile |
| RAG-G502 | Android Task 1.2：可信 VehicleProfile 与 Scope 解析 | COMPLETED | 2026-07-21 / 2026-07-21 | `VehicleProfileState`、`VehicleStateMachine` Profile Snapshot、`rag/profile/**` 与两组单测 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.profile.*' --console=plain`：通过 | Demo Profile 固定为 `DEMO_MODEL/2026/CN/DEMO_VERSION/DEFAULT`；跨端 Scope Golden 生成 `demo-model-2026-cn-demo-version-default` | Profile 不纳入八动态子系统通用 Patch；Provider 只读，空 Profile 稳定映射 `PROFILE_INCOMPLETE` | 进入 G503：严格 Manifest/Store 兼容校验与生命周期状态机 |
| RAG-G503 | Android Task 1.3：Manifest、Store Metadata 兼容校验与状态机 | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/store/**`（Manifest、解析器、兼容校验、生命周期、Lease、Manager、Gateway、文件摘要）及 Store 单元/设备测试 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.store.*' --console=plain`、`./gradlew.bat compileDebugAndroidTestJavaWithJavac --console=plain`：通过 | 严格 Manifest V1 解析、共享 Schema Golden、唯一 Metadata/计数/协议校验、READY 安装保持策略、只读 Gateway 与延迟关闭 Lease | Android 解析器消费共享 Schema 与 G404 开发候选；主状态与安装状态分离，升级失败保留有效 Store | 新增 Gateway 设备测试已编译；将在 G504 Asset 安装后以已授权 AVD 实际执行。进入 G504 |
| RAG-G504 | Android Task 1.4：Asset 安装、原子切换与崩溃恢复 | COMPLETED | 2026-07-21 / 2026-07-21 | `KnowledgeAssetInstaller`、`KnowledgeStoreCoordinator`、安全 Layout/Pointer、Asset/容量/候选验证边界、Service 生命周期接入及 JVM/设备测试 | `./gradlew.bat testDebugUnitTest assembleDebug connectedDebugAndroidTest --console=plain`：通过；Automotive API 35 AVD 实际执行 5 个设备测试 | 流式复制 + fsync、Hash/Metadata 试开验证、同版本内容冲突拒绝、active 临时指针原子提交、启动恢复、旧 Store 保持、无 main Asset 受控降级 | G404 候选仅进入 androidTest Asset；main APK 不携带开发知识库。Service 初始化只调度后台任务，`onDestroy` 幂等关闭协调器 | Android Phase 1 关闭；进入 G601，开始跨端词法 Analyzer 与 Query 协议 |
| RAG-G601 | Android Task 2.1：集中配置、Query 规范化与跨端 Lexical Analyzer | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/config/**`、`rag/retrieval/{QueryNormalizer,CjkLatinLexicalAnalyzer,Bm25Searcher,EmbeddingInputRenderer,RankedCandidate}` 与五组单测 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.retrieval.*' --console=plain`：通过 | Query NFKC/空白/ROOT lower/Code Point 限长/Hash；离线 CJK bi/tri-gram、故障码、缩略语规则；集中 BM25/RRF/预算基线 | 直接消费 `lexical-analyzer-v1.json` 和 `embedding-input-v1.json`，不重录 Golden | 进入 G602：Metadata eligibility、Dense、BM25 和开发 Bundle 本地查询 |
| RAG-G602 | Android Task 2.2：Scope/Metadata 资格过滤、Dense、BM25 与 Parent 延迟解析 | COMPLETED | 2026-07-21 / 2026-07-21 | `MetadataEligibility*`、`StoreScopeEligibilityPolicy`、`ObjectBox{Dense,Lexical}Searcher`、`ParentContextResolver`、Gateway 扩展及单元/设备测试 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.retrieval.*'`、`connectedDebugAndroidTest` 指定本地检索测试：通过 | Scope 先于查询校验；五维 exact/`*` filter；Dense 保存 Cosine Distance；posting BM25；Parent 不参与初始召回 | AVD 实测离线 Fixture 的 Dense/BM25 正向命中及 Region 失配零候选 | 进入 G603：绝对 Deadline、取消和受控云调用 |
| RAG-G603 | Android Task 2.3：可取消 DashScope Embedding/Rerank 与 Deadline | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/cloud/**`、MockWebServer 测试与 test dependency | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.cloud.*' --console=plain`：通过 | 调用 timeout=min(阶段上限, 剩余 Deadline−回答预留)；注册取消；仅重试网络/5xx；Embedding 1024/有限值校验；Rerank 索引范围/唯一性校验 | 请求体只含规范化 Query 或标题路径+短文本；Mock 断言不含 VIN/Profile/动态状态；不调用真实 API | 进入 G604：RRF、Evidence/Answerability/降级策略 |
| RAG-G604 | Android Task 2.4：RRF、Evidence 预算/去重、Answerability 与降级 | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/ranking/**`、`rag/policy/**` 与定向单元测试 | `./gradlew.bat testDebugUnitTest --tests 'com.hirain.aiagent.rag.policy.*' --tests 'com.hirain.aiagent.rag.ranking.*' --console=plain`：通过 | RRF 仅融合 Rank；Rerank 空结果保留 RRF；Evidence 按 ID/Parent/Hash 去重并施加 Unicode 词元预算；answerable 需要适用性+Locator+无硬失败 | Release 拒绝 TEST_ONLY；Embedding/Rerank 降级不放宽 Evidence/lexical 资格 | 进入 G605：服务编排、内部结果映射与 Android Phase 2 Gate |
| RAG-G605 | Android Task 2.5：VehicleKnowledgeService、结果映射与 Phase 2 Gate | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/retrieval/HybridRetrievalCoordinator.java`、`rag/VehicleKnowledgeService.java`、`VehicleKnowledgeServiceTest.java` | 定向服务测试通过；`./gradlew.bat testDebugUnitTest assembleDebug connectedDebugAndroidTest --console=plain`：通过，Automotive API 35 AVD 实际完成 6 个设备测试 | Lexical-first；Scope/Metadata 已在召回前过滤；有效 Rerank 才标记 `HYBRID_RERANKED`；Rerank 失败保留 RRF；Embedding 失败回退 Lexical-only；Store 生命周期、绝对 Deadline 与预取消均返回稳定结果码 | 云端路径全部由本地桩/Mock 验证，不发送真实资料或使用真实凭证；RAG 服务尚未接入 Agent Tool 链，严格留给 Phase 3 | Android Phase 2 关闭；进入 G701，先实现 KnowledgeNeedDetector 与 NONE/REQUIRED 路由 |
| RAG-G701 | Android Task 3.1：KnowledgeNeedDetector 与复合请求边界 | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/policy/{KnowledgeRequirement,KnowledgeNeedDetector,KnowledgeIntentDecision,RuleBasedKnowledgeNeedDetector,KnowledgeCapabilityPlanner}.java`；对应两组测试；`RuleBasedVisionIntentPolicyTest.java` | `./gradlew.bat :app:testDebugUnitTest --tests ...RuleBasedKnowledgeNeedDetectorTest --tests ...KnowledgeCapabilityPlannerTest --tests ...RuleBasedVisionIntentPolicyTest --console=plain`：通过 | V1 Detector 仅输出 NONE/REQUIRED；覆盖官方资料、故障码、条件、限制、版本差异；显式车控不升级；知识+车控及知识+前向视觉均在 Tool/60 秒 Deadline 前返回 `COMPOUND_REQUEST_REQUIRES_SPLIT`；仪表盘/车内不提升为前向视觉 | 本 Goal 不创建知识 ToolGroup，也不接入 Runtime；G703 在 Group 已创建后收敛 REQUIRED 的 Tool 集合 | 进入 G702：请求私有状态、60 秒 Deadline、运行时/线程上下文桥接 |
| RAG-G702 | Android Task 3.3：请求私有 Knowledge State、60 秒 Deadline 与上下文桥接 | COMPLETED | 2026-07-21 / 2026-07-21 | `KnowledgeRequestState`、`KnowledgeInvocationDecision`；`Request{Deadline,DeadlinePolicy,Session,SessionFactory,ExecutionContext}`；`AgentRuntime`；`AIAgentService.kt`；四组单测 | `:app:compileDebugJavaWithJavac`：通过；KnowledgeState/ExecutionContext/Deadline/SessionFactory 定向测试通过；`AgentRuntimeKnowledgeRoutingTest` 通过 | State 每 Session 新建、同步保护；同迭代至多一次、单请求至多两次、SHA-256 Query 去重、第二次必须更晚迭代；同源 Evidence ID 可复用；Runtime/Service ThreadLocal 共享同一 State，Scope finally 清理；REQUIRED 从 Service 起点派生 60 秒绝对 Deadline | 现有主模型单次 30 秒网络超时未放宽；完整 Tool 对 State 的实际调用留给 G703；无静态全局状态 Map | 进入 G703：新增知识 Group 与唯一 `searchVehicleKnowledge` Tool，并让 REQUIRED 收敛为唯一可见能力 |
| RAG-G703 | Android Task 3.2：Knowledge ToolGroup 与唯一 Tool 入口 | COMPLETED | 2026-07-21 / 2026-07-21 | `ToolGroupId`、`ToolGroupRegistry`、`KnowledgeCapabilityPlanner`、`VehicleKnowledgeTool`、ToolResult Mapper 及两组测试 | `:app:compileDebugJavaWithJavac` 通过；`KnowledgeCapabilityPlannerTest`、`VehicleKnowledgeToolTest` 通过 | `VEHICLE_KNOWLEDGE_GROUP` 只含 `searchVehicleKnowledge`，且未加入全量 Demo 聚合；REQUIRED 以替换而非合并方式收敛；Tool 只读 ThreadLocal requestId/deadline/state，缺失上下文 fail closed；Evidence 映射使用请求状态稳定 ID | 真实 Service 依赖实例与 ToolRegistry 生产注册由详细计划 Task 5.1 总装，不能在 G703 用临时全局状态伪造 | 进入 G704：所有 TEXT Tool 的 Allowlist 与 Knowledge Batch Policy，之后执行 Phase 3 Gate |
| RAG-G704 | Android Task 3.4：TEXT Tool Allowlist、Knowledge Batch Policy 与 Phase 3 Gate | COMPLETED | 2026-07-21 / 2026-07-21 | `core/policy/{ToolExecutionAuthorizer,ToolAuthorizationDecision}`、`TextAgentLoopOrchestrator`、授权/Registry/端到端测试 | `:app:testDebugUnitTest --tests ...ToolExecutionAuthorizerTest` 通过；`./gradlew.bat testDebugUnitTest assembleDebug --console=plain`：通过 | 授权来源仅为 ContextAssembly 的本轮 ToolSpecifications；任何隐藏 Tool 整批拒绝，写回每个 ToolResult，拒绝发生在 Safety/Dispatcher 前；REQUIRED 精确限制单个 `searchVehicleKnowledge` 调用 | 更新已有隐藏 Tool 端到端夹具为断言 `TOOL_NOT_AUTHORIZED`，未放宽生产策略；完整 Phase 3 设备测试不要求新增 Android Instrumentation 用例 | Android Phase 3 关闭；严格进入 G801（Phase 4 强制查证、证据/引用/Memory 策略） |
| RAG-G801 | Android Task 4.1：REQUIRED 强制 ToolLoop 协议 | COMPLETED | 2026-07-21 / 2026-07-21 | `KnowledgeLoopPolicy`、`KnowledgeLoopDecision`、`KnowledgeRequestState`、`VehicleKnowledgeTool`、`TextAgentLoopOrchestrator` 及策略测试 | `KnowledgeLoopPolicyTest`、`KnowledgeRequestStateTest` 通过；`TextAgentLoopOrchestratorTest` 全量通过 | 每迭代 reset；无可靠 Evidence 的 REQUIRED 使用 `ToolChoice.REQUIRED`；首轮未调用允许一次受控重试，第二次返回 `KNOWLEDGE_TOOL_NOT_CALLED`；两次不同 Query 无 Evidence 后返回 `KNOWLEDGE_EVIDENCE_UNAVAILABLE`；取消/超时沿既有短路不进入下一阶段 | 知识 Tool 的真实 Service 装配仍留 Task 5.1；本 Goal 不实现 Citation 或持久化投影 | 进入 G802：请求内完整 ToolResult Buffer、Session 紧凑投影与长期记忆抑制 |
| RAG-G802 | Android Task 4.2：KnowledgeTurnBuffer、紧凑持久化与长期记忆抑制 | COMPLETED | 2026-07-21 / 2026-07-21 | `KnowledgeTurnBuffer`、`KnowledgeMemoryPolicy`、`KnowledgeRequestState`、`TextAgentLoopOrchestrator`、`SessionMemoryContextProvider` 与定向测试 | `SessionMemoryContextProviderTest`、`TextAgentLoopOrchestratorTest`、`KnowledgeTurnBufferTest`、`KnowledgeMemoryPolicyTest`：通过；主源码编译通过 | 完整知识 ToolResult 以 toolCallId 仅存当前 Request Buffer；SessionMemory 只写受控紧凑投影；当前 Request 后续迭代按 toolCallId 覆盖回完整结果；新请求无 Buffer，无法恢复历史 Evidence；REQUIRED 跳过长期记忆提取 | 紧凑投影仅保持会话可读，不得进入 Citation Map；Citation 真实性由 G803 处理 | 进入 G803：CitationGuard、Grounding Prompt 与 PDF/HTML/Markdown SourceLocator 渲染 |
| RAG-G803 | Android Task 4.3：CitationGuard、Grounding Prompt 与三格式来源渲染 | COMPLETED | 2026-07-21 / 2026-07-21 | `CitationGuard`、`KnowledgeRequestState`、`VehicleKnowledgeTool`、`TextAgentLoopOrchestrator`、`PromptContextProvider`、知识 Grounding Prompt 及定向测试 | `CitationGuardTest`、`SourceCitationRendererTest`、`ContextProviderRequiredPolicyTest`：通过 | REQUIRED 最终回答至少含一个本轮 Evidence Map 中的 `[E#]`；未知或缺失引用受控拒绝；受控 Locator 渲染 PDF 印刷页/物理页、HTML 锚点、Markdown 行号；知识约束仅注入 REQUIRED Prompt | 来源文字由当前请求的受控 Evidence 生成，模型不能自行伪造 Locator；普通聊天不继承知识提示词 | 进入 G804：Trace、脱敏、错误映射与 Android Phase 4 Gate |
| RAG-G804 | Android Task 4.4：RAG Trace、隐私、稳定错误映射与 Phase 4 Gate | COMPLETED | 2026-07-21 / 2026-07-21 | `rag/trace/{RagTraceRecorder,RagTraceSnapshot}`；`RequestExecutionContext`、`AIAgentService.kt`、`VehicleKnowledgeTool`、Trace 常量、响应映射、API 兼容修复及测试 | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain`：通过 | `tool.searchVehicleKnowledge` 请求私有 `rag.retrieve` Span；Trace DTO 无 Query/正文槽位，仅记录 Hash/ID/计数/模式/时间/错误码；异常及未知错误不向 AIDL 文本泄露内部详情 | Lint 暴露并修复 RAG 检索中三个 API 34 `Stream.toList()` 调用，minSdk 33 兼容；Phase 4 所列单测、构建、Lint 均已实测 | Android Phase 4 关闭；严格进入 G850 Service 生产装配与开发 Bundle 联调 |
| RAG-G850 | Android Task 5.1：Service 生产装配与开发 Bundle 联调 | COMPLETED（开发联调） | 2026-07-21 / 2026-07-21 | `AIAgentService.kt`、RAG Store/Cloud/Retrieval/Policy/Tool 既有实现 | `:app:testDebugUnitTest --tests VehicleKnowledgeServiceTest --tests AgentRuntimeKnowledgeRoutingTest :app:assembleDebug`：通过；`connectedDebugAndroidTest`：Automotive API 35 `x86_64` AVD 历史全量开发联调 6 个测试通过；本轮聚焦 Store 的 3 项 Instrumentation 复验通过，见 `docs/testresult/rag/android_rag_store_instrumentation_report.md` | Service 共享 `RequestCallRegistry`，异步 Store 初始化，构造并注册唯一知识 Tool；开发候选在 androidTest Asset 中通过真实安装、ObjectBox 打开、Dense/BM25/Scope 查询；Service 销毁先停止准入/取消请求再关闭 Store | 开发候选仍不放入 main APK；无 main Asset 时只使知识能力失败关闭，普通能力不依赖 RAG READY | G850 仅表示 Android 代码与开发 Bundle 已联调，不表示正式候选、真实阈值或发布就绪；严格进入 G901 |
| RAG-G901 | 离线 Task 5.1：可审计 Fixture 与 Golden 结果 | COMPLETED | 2026-07-21 / 2026-07-21 | `tools/rag-indexer/src/test/resources/fixtures/**`；`fixture/{PdfFixtureFactory,CorpusFixtureFactory,FixtureAuditTest}`；`golden/GoldenFileAssertions` | `gradlew.bat clean test check installDist distZip --console=plain`：通过；Fixture 文本扫描未发现 API Key 或绝对路径 | PDF 由 Java 测试工厂生成，HTML/Markdown/安全/跨格式均为本地合成语料；每类含来源、许可和预期说明；Golden 保存受控摘要及变更原因 | 不使用真实车辆资料或不透明 PDF 二进制；真实数据的授权、审核和评测保持 G903 前置条件 | 进入 G902：全链路、安全、确定性、取消和故障注入回归 |
| RAG-G902 | 离线 Task 5.2：全链路、安全、确定性、取消与故障注入 | COMPLETED（测试矩阵） | 2026-07-21 / 2026-07-21 | 既有 `pipeline/**`、`artifact/**`、`embedding/**`、`parser/html/**`、`corpus/**`、`store/**` 测试及 G901 Fixture | `gradlew.bat clean test check installDist distZip --console=plain`：通过 | 覆盖 Build Cancellation、Checkpoint、确定性时钟/排序、TEST_ONLY/APPROVED Bundle、Verify 篡改、原子发布、HTML 静态离线资源边界、路径/格式安全、Embedding 批序/缓存/重试/向量校验、Store 损坏与 Manifest 一致性 | G902 复用已存在且有针对性证据的测试，不以新增空壳 e2e 类替代真实覆盖；真实资料质量评测不属于本 Goal | 进入 G903：必须先核实真实资料授权、Metadata 审核和评测集 |
| RAG-G903 | 真实资料校准与正式候选前置条件核查 | IN_PROGRESS（PDF+HTML 审核完成，扩展 Dense 校准进行中） | 2026-07-22 / - | `tools/rag-indexer/corpus/model_y_2026_refresh_trial/**`、`evaluation/**`、离线 CLI/Chunk 诊断修复、`docs/testresult/rag/**` | 原始 PDF+HTML Corpus增强 `validate` 通过并生成页码级 JSON 审核报告与只限本机的 HTML 处理预览；资料负责人已确认重复页眉/页脚、URI 忽略、HTML 清洗与 Scope 正确，并将 PDF 表格记为初期不保证覆盖的 `TEST_ONLY` 边界；2 文档 Bundle `verify` 输出 `VERIFY_SUCCESS`；`evaluate` 以只读 Store 对 3 Case HTML 用例运行：HTML-only 基线 Recall@1/@3/@5、MRR=`1.0`，在 PDF+HTML 全量库复跑后均为 `0.667`；已有输出目录的 CLI 预检稳定返回 `BUILD_OUTPUT_ALREADY_EXISTS`（退出码 6），不重建、不覆盖 | `model-y-2026-refresh-v1`：2 文档、1,993 Parent、4,360 Child、87,990 词项、4,360/4,360 Embedding 成功；`data.mdb` SHA-256=`9e8461b53032bbbc6e618bce132f817b636e19205554406501f6bffeceaae874`；PDF 9,900 Block、37 条解析诊断和 561 个 WARNING 正文块定位；脱敏评测报告与审核材料均位于本地忽略 work 目录；人工审核入口见 `docs/testresult/rag/g903_real_corpus_release_gate_checklist.md` | Scope 固定 `MODEL_Y/2026/CN/2026_REFRESH/RWD`；用户授权云端处理；真实资料和输出目录本地忽略；所有试运行 Bundle 明确为 `publishable=false`；评测报告不保存 Query 正文；不尝试 PDF 密码、解密绕过或修改原文件 | 必须补 PDF 评测用例并完成 Chunk/HNSW 校准；3 Case HTML 基线不能替代完整 PDF+HTML 评测、Android Hybrid/Rerank、No-Evidence、Citation 或 Faithfulness；完成其余门禁前不得进入正式候选 |

## 工作区基线

## G903 最新增量记录（2026-07-22）

| 项目 | 结果 |
|---|---|
| 人工审核依据 | 资料负责人已在本机 `parser-review.html` 审阅实际 PDF/HTML 解析预览并确认无问题；结论仅覆盖当前 `TEST_ONLY` 资料范围。 |
| 新增评测集 | `model-y-2026-refresh-pdf-html-baseline-v2`，共 5 条：既有 HTML 3 条、已审核 PDF 正文 2 条。 |
| Bundle | `model-y-2026-refresh-chunk-baseline-v2`，`data.mdb` SHA-256=`21106cd8fcf72639b7cf2d0b81345b42c73d17a8762f028fe812cbe7503ef296`。 |
| 只读 Dense 评测 | `EVALUATION_SUCCESS cases=5`；逐条核验后采用多分块 Ground Truth，Recall@1/@3/@5=`0.600/1.000/1.000`，MRR=`0.767`。初始单分块诊断报告仍保留，本次未覆盖。 |
| 结论 | 跨格式评测链路已跑通，但两个 PDF 问题分别只在第 3、2 位命中，故不能锁定质量阈值、不能升级 `APPROVED`、不能进入正式候选。 |
| 下一步 | 分析失败样本，并按 G903 继续补充 PDF/HTML 评测集与检索校准证据；后续才可讨论 G904 正式候选。 |

### G903 HNSW 协议修复增量（2026-07-22）

| 项目 | 结果 |
|---|---|
| 发现 | 原实现只将维度与 Cosine 纳入 HNSW 指纹，未显式固定 ObjectBox 已支持的 `neighborsPerNode`、`indexingSearchCount` 等参数，不能满足 G903 的参数对比前提。 |
| V1 显式基线 | `M=30`、`efConstruction=100`、回链概率 `1.0`、缓存提示 `0`、无 flags；这些值保持 ObjectBox 5.4.0 既有默认语义，只消除隐式默认值漂移。 |
| 协议保护 | 共享 Entity 注解、离线 HNSW 指纹/Manifest、Android 严格 Manifest Parser 与 Store 兼容校验均已同步；Android 会拒绝任一 HNSW 参数漂移。 |
| 已验证 | 离线全量 `clean test check installDist distZip` 通过；Android `testDebugUnitTest` 通过；离线/Android HNSW 定向契约测试通过。 |
| 数据产物 | V3 已构建并 `VERIFY_SUCCESS`：`data.mdb` SHA-256=`48ac68254d07ed24ed5bd90d5499315af3d93146704dbd90eb667f60a35d9d47`，`publishable=false`。旧 V2 保留、不覆盖，且不再视为新协议下的跨端候选。 |
| 首轮对比 | 同批次 V2 为 Recall@1/@3/@5=`0.600/1.000/1.000`、MRR=`0.767`；显式 `M=30/ef=100` V3 为 `0.200/0.600/0.600`、MRR=`0.367`。V3 不能批准。 |
| 后续 | 以新的独立候选比较其他显式 HNSW Profile，并记录 Android 查询延迟；完成前不得将任一候选提升为 `APPROVED`。 |

### G903 V4 扩展资料与 Hybrid 质量校准（2026-07-23）

| 项目 | 结果 |
|---|---|
| 资料接入 | 新增 DIY 操作指南目录和中国大陆服务中心静态 HTML；与既有用户手册 PDF、保修 HTML 一起构成 4 个逻辑来源。DIY 的 64 个内容页聚合为 1 个逻辑文档，未把导航 `index*.html` 当正文，也没有修改原始资料。 |
| 静态安全边界 | DIY 目录限制最多 96 页、逐页校验在 Corpus Root 内并以相对路径+哈希形成确定性来源指纹。服务中心页仅读取本地 `__NEXT_DATA__` JSON 中的服务中心记录；不执行 JavaScript、不请求网络、不加载动态页面资源。 |
| 解析审阅 | 新运行 `f34cb8be-7ec8-4e2c-98fe-6957706ea80a` 已通过 `validate`；DIY 产生 878 个正文块、6 个表格块，服务中心产生 572 个正文块，均无解析诊断。审阅页：`corpus/model_y_2026_refresh_trial/work/runs/f34cb8be-7ec8-4e2c-98fe-6957706ea80a/parser-review.html`。 |
| V4 候选 | `TEST_ONLY-model-y-2026-refresh-v4-expanded`：4 Document、2,401 Parent、5,242 Child、100,561 词项，5,242 个向量全部成功。`verify` 输出 `VERIFY_SUCCESS`；`data.mdb` SHA-256=`d132b8c9da49d1c41328a336d21db81f72e1e21daae2e0f854191e5f1959bb72`。 |
| 评测集 | 新增 `evaluation_pdf_html_diy_service_v4.json`，共 20 条受控问题，覆盖保修、维护、DIY、服务预约和北京/上海服务中心；构建后已验证全部 Ground Truth Chunk ID 存在。 |
| 评测口径修正 | 旧离线评测只计算 Dense，无法代表 Android 实际的 Dense Top20 + BM25 Top20 + RRF(k=60) + Top5。新增 `OfflineHybridEvaluator` 后，CLI 会按 Android 当前融合顺序执行，模式标识为 `HYBRID_FUSION_ONLY_V1`；本轮没有伪造云端 Rerank 结果。 |
| 量化结果 | 同一 20 条用例中，Dense 为 Recall@1/@3/@5=`0.4000/0.8500/0.8500`、MRR=`0.6000`；Android 对齐 Hybrid 为 `0.5000/0.8500/1.0000`、MRR=`0.6908`。融合使全部预期证据进入 Top5，但 Top1=0.50，不能批准为生产质量。 |
| 验证 | 修复既有 HNSW Manifest 正向测试夹具后，离线 `gradlew.bat test`（126 tests）和 `installDist` 均通过；V4 `build`、独立 `verify`、Hybrid `evaluate` 全部通过。 |
| 决策 | 不再继续盲调 HNSW。当前首要质量缺口是来源覆盖、可解释融合结果与 Android 真机/模拟运行验收，而不是继续增大 HNSW 参数。 |
| Android 设备证据 | 已将 V4 仅复制到 `androidTest` 生成 Asset，在 Automotive API 35 `x86_64` AVD 成功完成真实流式安装、Hash/Manifest/Store Metadata/Model Y Scope 校验，以及 BM25、Dense 查询。首次运行暴露 Android Manifest Parser 未声明 `embeddedDataExtraction`；已将该字段升级为共享协议，旧 Bundle 缺省兼容，未知策略拒绝，定向 JVM 与设备测试均通过。主 APK 仍不携带 V4。 |
| 未关闭风险 | 未完成真实 Query Embedding/RRF、Rerank、无证据/冲突/车型地区不匹配、Citation/Faithfulness、PDF 表格和目标 `arm64-v8a` 验收。DIY 聚合结果保留原始 HTML 锚点，但 `SourceLocator` 未存原始相对文件路径；若正式引用要精确到原始页面，需扩展协议。 |

本次校准的详细指标和根因分析见 `docs/testresult/rag/offline_chunk_retrieval_evaluation.md`。V4 继续保持 `TEST_ONLY`，不得仅因离线 Recall@5 达到 1.0 而提升为 `APPROVED`。

### Git 状态

首次检查到的既有未跟踪项如下，均视为用户或前序工作产物，RAG 实施期间不得删除或覆盖：

```text
?? docs/overview/1cd06051-cad2-4525-acc6-130af4525356.png
?? docs/plan_overall/rag/
```

### 工程范围

- Android 主工程：当前仓库根目录；RAG 运行时后续进入现有 `app` 模块。
- 离线构建端：后续创建在 `tools/rag-indexer/`，保持独立 Java 17 Gradle Build，禁止加入 Android 根 `settings.gradle.kts`。
- 共享规范源：后续由离线 `RAG-G003` 建立 `rag-schema/`；在此之前不得创建竞争性的 Schema 或 ObjectBox Meta Model。
- 明确排除：`D:\code\android\AndroidStudioProjects\AIAgent_RAG` 外部目录不在本次实现范围内，未读取也未写入。

## 外部输入与环境事实

| 输入或环境 | 当前状态 | 最迟 Gate | 处理原则 |
|---|---|---|---|
| 依赖许可证、商业发布确认及审批人 | 未提供 | RAG-G002 | 不伪造法律结论；Gate 前仅可完成事实清单和候选依赖审查。 |
| ObjectBox 目标 ABI 设备或等效验证环境 | 已通过：Automotive API 35 `x86_64` AVD | RAG-G008 | 项目负责人已授权使用虚拟设备；Android 详细计划明确允许“目标 ABI 或等效环境”执行 Instrumentation。最终正式发布仍需目标 ABI 复测。 |
| DashScope API、数据发送授权 | 已获准，已完成真实静态 HTML 的受控试运行 | RAG-G303 / RAG-G903 | 凭证不写入代码、文档、报告或日志；真实资料输出仍仅为 `TEST_ONLY`。 |
| 获准的 PDF、静态 HTML、Markdown 语料与 Metadata | 已提供 1 PDF、3 个逻辑 STATIC_HTML 来源（保修、DIY 目录、服务中心）；暂无 Markdown；Metadata 仅有用户确认，尚未人工复核 | RAG-G903 | V4 `TEST_ONLY` 扩展候选已通过独立 Verify 和 20 条 Hybrid 离线评测；PDF Warning、资料完整性、Markdown、正式审核和 Android 端验收仍未完成，禁止伪装为正式 Bundle。 |
| 发布阈值、资源预算、最终发布确认 | 未提供 | RAG-G905-G999 | 记录实测值并等待人工批准。 |

### 基线命令结果

| 项目 | 结果 | 说明 |
|---|---|---|
| `java -version` | 已执行 | 当前命令行 JDK 为 Java 21.0.10；离线构建端要求 Java 17，RAG-G001 必须使用 Gradle Toolchain 或已安装的 Java 17，不能把 Java 21 基线当作兼容性证据。 |
| `adb devices` | 已执行等效设备验证 | 使用 SDK 中的 `platform-tools/adb.exe` 启动 `Automotive_1408p_landscape`；设备为 API 35、`x86_64`，不是目标 `arm64-v8a`。 |
| DashScope 环境变量存在性 | 未发现 | 当前进程未发现 `DASHSCOPE_API_KEY`；未读取任何凭证值。 |
| `./gradlew.bat testDebugUnitTest --console=plain` | 通过 | 构建成功，24 个任务中 8 个执行、16 个最新；出现既有 Manifest 重复权限警告与一个弃用 API 编译提示，均非本 Goal 新增失败。 |
| `tools/rag-indexer/gradlew.bat test installDist --console=plain` | 通过 | CLI Smoke Test、Java 17 编译与安装分发通过。 |
| `tools/rag-indexer/gradlew.bat distZip --warning-mode all --console=plain` | 通过 | 生成 7,668 字节 ZIP；内容仅含索引器 JAR 与启动脚本，无 Android SDK 或 AAR。 |
| `tools/rag-indexer/gradlew.bat -q javaToolchains` | 通过 | 检测到 Microsoft JDK 17.0.17，位于用户 JDK Toolchain 目录。 |
| `./gradlew.bat projects --console=plain` | 通过 | Android 根工程仍只包含 `:app`。 |
| `./gradlew.bat connectedDebugAndroidTest --console=plain` | 通过 | Automotive API 35 `x86_64` AVD 共执行 2 个测试；其中 RAG 测试已实际打开 Fixture 并验证 Dense、Lexical、Scope/Metadata 查询。 |

## 验证记录

后续每个 Goal 在此追加命令、结果、未执行原因、产物路径与必要 Hash。不得把“命令未运行”写为通过。

### RAG-EV2-G104 Phase E 增量：小 Paragraph 阈值与 PDF 目录层级校正（2026-07-25，自动验证完成，等待人工 Chunk Gate）

| 项目 | 记录 |
|---|---|
| 计划修订 | 已修订 `docs/plan_overall/eval/09-rag-pdf-line-paragraph-child-rechunking-plan.md`：新增 `<30 token` 强制吸附、`30～100 token` 直接合并、`>100 token` 才进入 Embedding 判断的 Child 规则；新增 PDF 第 3/4 页目录作为层级参考、但不进入正文 Evidence 的协议。 |
| Child 阈值实现 | `ChunkingConfig`、`ConfigLoader`、`ConfigValidator`、`ManifestBuilder`、共享 Manifest Schema 和 `ChunkBoundaryPolicy` 已同步 `childForceMergeMaxTokens=30`、`childDirectMergeMaxTokens=100`、`pdfTocReferenceVersion=pdf-toc-reference-v1`。 |
| 小 Paragraph 行为 | 普通 Paragraph 小于 30 token 时优先吸附前/后邻接 Child，且不超过 512 token；30～100 token 不调用 Embedding 直接尝试合并；超过 100 token 才使用已有 Embedding 相似度策略。不得跨 Parent、Warning、列表/表格等原子语义组强行合并。 |
| PDF 目录行为 | `PdfTocReferenceExtractor` 识别物理第 3、4 页目录并解析标题/层级；`PdfHeadingHierarchyNormalizer` 优先使用目录条目校正正文标题层级；`PdfDocumentParser` 将目录页排除出最终 Block/Parent/Child/Evidence，但保留 `PDF_TOC_PAGE_EXCLUDED` 与 `PDF_TOC_REFERENCE_APPLIED` 诊断。 |
| 自动化测试 | 直接 JDK 编译：主源码与测试源码均通过；最近一次完整 JUnit 运行结果为 `157 tests / 157 successful / 0 failed`。本轮尝试使用 Gradle 重跑时被本机 Gradle wrapper 锁文件访问拒绝阻断，未将该次失败记为测试通过。 |
| 最新 validate | `tools/rag-indexer/build/final-e9-validate/runs/9b5b0732-b7f4-4c84-bb1e-23bb16d34193/`，命令返回 `VALIDATION_PASSED`。 |
| 分块统计 | Parent=`951`、Child=`1898`、Parent over hard=`0`、Child over hard=`0`、Parent overlap=`19`、Child `<30`=`174`、Child `30～100`=`812`、`SMALL_PARAGRAPH_UNMERGED`=`50`。其中残留 `<30` 主要来自 Parent-as-Child、Warning/列表等原子组或无合法邻接段落，属于结构边界保护，不代表普通 Paragraph 合并规则失效。 |
| 解析统计 | Model Y PDF Block=`12607`；PDF 目录排除诊断 2 条（物理页 3、4），目录参考应用诊断 1 条；DIY、服务中心、保修 HTML 均无新增解析错误。 |
| 人工审核入口 | [parser-review.html](../../../tools/rag-indexer/build/final-e9-validate/runs/9b5b0732-b7f4-4c84-bb1e-23bb16d34193/parser-review.html)；[chunk-review.html](../../../tools/rag-indexer/build/final-e9-validate/runs/9b5b0732-b7f4-4c84-bb1e-23bb16d34193/chunk-review.html)；[chunk-quality.json](../../../tools/rag-indexer/build/final-e9-validate/runs/9b5b0732-b7f4-4c84-bb1e-23bb16d34193/chunk-quality.json)。优先检查 PDF 标题路径、跨页 Parent、`<30 token` Child 的结构原因及两列顺序。 |
| Gate 状态 | 09 分块实现已按项目负责人确认完成；标题协议阶段允许复用现有稳定 Parent/Child 产物。正式 `APPROVED` 仍需新的索引 Bundle、Eval V2 和人工验收，不得将任何 TEST_ONLY 产物直接发布。 |

### RAG-EV2-G105 标题检索协议适配（2026-07-25，实施中）

| 项目 | 记录 |
|---|---|
| 计划归属 | 已将标题检索协议补入 `docs/plan_overall/eval/08-rag-parent-child-retrieval-eval-v2-implementation-plan.md`。本阶段沿用 09 已生成的 Parent/Child 和稳定 ID，不重新执行 09 的解析与分块。 |
| Dense 输入 | 新增 Embedding V2：`二级标题 + Child 正文`；离线端和 Android 端共享模板，Embedding Cache Key 使用模板版本 2。 |
| BM25 输入 | 改为 TITLE/BODY 独立字段 posting，分别记录 TF/DF、字段文档长度和平均长度；初始得分为 `TITLE × 2.0 + BODY × 1.0`。 |
| Schema | ObjectBox `KnowledgeChunkEntity` 新增 `parentTitle`、标题/正文词长；`LexicalTermEntity` 新增字段标识；Metadata 新增字段平均长度、权重和版本；共享 Meta Model 与 SHA-256 已同步。 |
| Android | `ObjectBoxLexicalSearcher`、Manifest Parser、Store Compatibility Validator 已适配 V2 字段协议。 |
| 验证 | Gradle Wrapper 因本机 Gradle 缓存锁权限无法运行；已使用 JDK 直接编译离线主源码、ObjectBox Schema 注解处理器和 Android 受影响 Java 类，均通过。真实 Embedding 重建完成，Bundle 独立 `VERIFY_SUCCESS`。 |
| Bundle | `tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`，Parent=`951`、Child=`1878`，`data.mdb` SHA-256=`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`，保持 `TEST_ONLY`。 |
| 独立 Verify | 使用 `rag-build-v2-review.json` 重新执行 `verify`，返回 `VERIFY_SUCCESS`；Manifest 的 Embedding templateVersion=`2`、BM25 analyzerVersion=`2`、Scope=`model-y-2026-cn-2026-refresh-rwd`。 |
| Eval | 旧 V4 评测集包含上一版 Child ID，直接评测新 Bundle 返回 `EVALUATION_EXPECTED_CHUNK_UNKNOWN`；这说明 Ground Truth 需要按 09 新稳定产物重新核验，不能把旧指标当作标题协议结果。 |
| 当前状态 | 标题协议和索引 Bundle、Parent Evidence 链路及 Eval V2 自动评测均已完成；人工样本 Gate、DEV/TEST 冻结和跨端设备验收仍待完成。 |

### RAG-EV2-G205/G206 Child→Parent Evidence 链路（2026-07-25，代码闭环完成，等待 Eval V2）

| 项目 | 记录 |
|---|---|
| Parent 批量读取 | `KnowledgeStoreGateway.parentChunksByIds` 与 ObjectBox 实现已加入；查询输入去重，结果由调用方按候选排名重排，并只接受 `chunkLevel=PARENT`。 |
| Parent 聚合 | `ParentCandidateAggregator` 按 Rerank 后 Child 顺序聚合；同一 Parent 只保留一次，最佳 Child 作为 Parent 排名代表，并保留 supportingChildIds 等内部诊断。 |
| Evidence 恢复 | `HybridRetrievalCoordinator` 已改为 Child Dense/BM25 → RRF → Child Rerank → Parent 映射 → 完整 Parent Evidence；最终 `content` 不再是 Child。Rerank 不可用时使用 RRF 代表，不伪造 Rerank 分数。 |
| 预算与去重 | `ParentEvidenceBudgetPolicy` 默认最多 4 个 Parent、总预算 5000 Token，超预算停止并不截断 Parent；`EvidenceDeduplicator` 改为按 Parent/内容去重。 |
| 模型白名单 | `VehicleKnowledgeEvidence` 增加 `sectionPath` 与 `retrievalConfidence`；内部 Parent ID、Child ID、RRF/Rerank 原始分数仍不进入 ToolResult JSON。置信度当前统一为 `UNASSESSED`，等待人工 Eval 校准。 |
| 验证 | 直接 JDK 编译受影响 Android 类通过；JVM 选定回归测试 `11 tests / 11 successful / 0 failed`，新增 Parent 聚合与 Parent 预算测试通过。Gradle Wrapper 仍受本机缓存锁权限阻断，未把该阻断记为代码通过。 |
| 当前边界 | 旧 V4 Eval Ground Truth 仍不可复用；旧版 51 条 Eval V2 已完成历史验证，新版 50 条已按人工反馈重建并通过结构校验，但人工复核、阈值校准和新版指标尚未完成。Bundle 继续保持 `TEST_ONLY`。 |

### RAG-EV2-G301/G302/G303 Eval V2 与 Parent Evidence 评测（2026-07-25，自动验证完成，人工 Gate 未关闭）

| 项目 | 结果 |
|---|---|
| Schema/Loader | 新增 `retrieval-evaluation-v2.schema.json`、严格 Loader、`EvidenceSetExpectation`、Answerability/Category 领域模型；未知字段、重复 Query/ID、ANSWERABLE/NO_EVIDENCE 冲突会拒绝。 |
| 评测集 | `evaluation_parent_evidence_v2.json` 当前为 50 条 ANSWERABLE，`DEV=20`、`TEST=30`，覆盖 WARRANTY、VEHICLE_OPERATION、DIY_OPERATION、SAFETY、SERVICE_CENTER 和 OTHER；新版已通过 `parents=951 cases=50` 校验。旧版 51 条仅保留在 `work/evaluation_parent_evidence_v2_before_review.json`。 |
| Parent 主指标 | 报告计算 Coverage@1/@2/@3/@4、MRR、NoEvidenceAccuracy，并以最终完整 Parent Evidence（最多 4 个、5000 token）判定，不再以裸 Child 命中作为成功。 |
| 候选诊断 | 离线端与 Android 均执行 RRF 后去重：最多 30 个融合候选、同 Parent 最多 2 个占位、完全重复和 Jaccard 高相似候选删除；报告记录 Exact/Near/Parent Occupancy 丢弃计数和 Evidence Token 使用量。 |
| RRF 结果 | `docs/testresult/rag/evaluation_parent_evidence_v2_rrf_final3.json`：Coverage@1/@2/@3/@4=`0.5625/0.7292/0.7500/0.8542`，MRR=`0.6788`，NoEvidenceAccuracy=`0.3333`；Exact/Near/Parent 占位丢弃=`35/8/298`，Evidence 最大=`4305 token`。 |
| Rerank 结果 | `docs/testresult/rag/evaluation_parent_evidence_v2_rerank_final3.json`：Coverage@1/@2/@3/@4=`0.5417/0.6875/0.8125/0.8542`，MRR=`0.6667`，NoEvidenceAccuracy=`0.6667`；Evidence 最大=`4821 token`。当前数据不支持宣称 Rerank 全面优于 RRF。 |
| 无证据策略 | 新增 `OFFLINE_SHARED_PHRASE_V1_TEST_ONLY`，用于诊断“相关但不足以回答”的越界问题；阈值尚未通过 DEV 集校准，不能作为生产拒答策略。 |
| 人工 Gate | 新版已预分 `DEV=20`、`TEST=30`，但仍需项目负责人对新增样本逐条确认，并完成置信度校准和最终 TEST 单次验收；说明见 `docs/testresult/rag/parent_evidence_eval_v2_dataset_review.md`。 |
| 审核资产 | 已生成新版本地审核页 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-authoring-v3.html`（50 条 Case、完整 Parent 正文、Evidence Set 和 DEV/TEST 选择）；导出文件入口为同目录 `eval-v2-review.json`。 |
| Rerank 差异审核 | 已生成 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-rerank-review-v1.html`，展示 RRF/Rerank Parent 排名差异、完整 Parent 正文和人工结论入口；不参与自动指标。 |

### RAG-EV2-G204/G203 去重与离线 Rerank 链路增量（2026-07-25）

| 项目 | 结果 |
|---|---|
| Android 候选链 | `FusionCandidateDeduplicator` 已接入 `HybridRetrievalCoordinator` 的 RRF→Rerank 之间；保留完整 Child，不跨 Parent 去重，Rerank 失败仍保留 RRF 顺序。 |
| 离线一致性 | `OfflineHybridEvaluator` 使用相同的 30 候选、同 Parent 2 位、Exact/Near 去重规则；报告只记录计数，不保存正文。 |
| 定向测试 | `FusionCandidateDeduplicatorTest` 2/2 通过；Eval V2 Loader 与无证据策略测试 3/3 通过；受影响 Android 类直接 JDK 编译通过。 |
| Gradle 状态 | Android Gradle Wrapper 受本机 Gradle 8.11.1 分发锁文件权限拒绝阻断，直接调用已缓存 Gradle 又因 `native-platform.dll` 无法加载失败；本轮未把 Gradle JVM/Assemble/Lint 记为通过。 |

### RAG-EV2-G307/G308/G309/G310 验收补充（2026-07-25，自动验证完成，AVD 外部阻塞）

| 项目 | 结果 |
|---|---|
| V2 协议 Fixture | 将共享开发候选 Fixture 迁移为 Manifest V2；解析器补齐实际 V2 分块字段（段落恢复、两列布局、目录参考、短 Paragraph 阈值等），Manifest Parser、Schema Golden、Installer、Recovery 定向测试全部通过。 |
| Android JVM | 使用本地 Gradle 8.11.1 + JDK 17.0.17 + `-Pkotlin.compiler.execution.strategy=in-process` 执行 `:app:testDebugUnitTest`，`452 tests completed, 0 failed`。 |
| APK/Lint | `:app:assembleDebug :app:lintDebug` 通过；曾发现的 API 34 `Stream.toList()` 已改为 API 33 兼容的 `Collectors.toList()`。Lint 仍有 34 条 warning，但无 error。 |
| AndroidTest 资产 | `prepareRagTestAssets` 已切换至 `tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`，复制到 `rag/model_y_title_v2_candidate`；不进入 main APK，Bundle 仍为 `TEST_ONLY`。 |
| AVD | 已发现 `Automotive_1408p_landscape`，但启动后 ADB 无 connected device、`sys.boot_completed` 为空；`connectedDebugAndroidTest` 明确失败 `No connected devices!`。该项是本机 AVD 启动阻塞，不是代码测试通过。 |
| 当前 Gate | Android JVM、Debug APK、Lint 已完成；AVD 设备验收、人工 Eval Gate、DEV/TEST 冻结和置信度校准仍未关闭；不得提升 `APPROVED`。 |

### RAG-EV2-G311 离线 CLI 回归补充（2026-07-25，自动验证完成）

| 项目 | 结果 |
|---|---|
| CLI 测试 | 使用 JDK 17 和本地 Gradle 8.11.1 执行 `tools/rag-indexer` 的 `test installDist`，`160 tests completed, 0 failed`，Smoke Test、JUnit 5 和安装分发均通过。 |
| 修复项 | Eval V2 测试统一为 JUnit 5；字段化 LexicalIndex 保留稳定 Child ID 顺序；共享 ObjectBox Model fingerprint 测试基线同步到标题字段 V2。 |
| 当前 Gate | 离线 CLI、Android JVM、APK 和 Lint 均自动通过；仅 AVD 设备、人工审核、DEV/TEST 冻结和置信度校准未完成。 |

| CLI 分发包 | `tools/rag-indexer/gradlew distZip --warning-mode all` 通过，桌面 CLI 分发包已重新生成；仍不包含 Android APK 或正式知识库发布资产。 |

| 计划原命令复核 | 按 Phase 3 原命令执行 `clean test check installDist`；`clean` 因 Windows 进程占用 `build/install/rag-indexer/lib` 文件失败，已停止 Gradle daemon 并用 `--no-daemon` 重试仍失败。此前非 clean 的 `test installDist` 与 `distZip` 已通过，但不能把本次 clean 失败写成通过。 |

| README 收口 | 根目录 `README.md` 已新增 RAG Parent-Child V2 当前边界、Bundle 状态、自动化验证结果和未关闭 Gate；未把 `TEST_ONLY` 资产描述为正式发布能力。 |

| 人工审核页补充 | `ParentEvidenceManualReviewWriter` 已支持每条样本导出 `decision`、`note` 和 `split=DEV/TEST`；已生成新版本地审核页 `work/eval-v2-authoring-v2.html`（51 条、102 个分组单选项）。旧审核 JSON 仍兼容，但当前仍为 `PENDING_MANUAL_REVIEW`。 |

### RAG-EV2-G312 Eval V2 人工审核反馈重建（2026-07-25）

| 项目 | 结果 |
|---|---|
| 审核输入 | 已采用用户导出的 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-review.json`；未把未审核样本擅自标记为最终通过。 |
| 删除与改写 | 删除 17 条 `REMOVE`，改写 2 条 `REWRITE`，修正 2 条 `GROUND_TRUTH_REVISE`；旧 51 条数据备份为 `work/evaluation_parent_evidence_v2_before_review.json`。 |
| 去重 | 合并同一 Parent 下的同义问题，避免轮毂螺母罩、气囊、手机 App、滤清器、服务中心电话/地址等重复占用评测名额。新版 50 条 Query 全部唯一，主 Parent 全部唯一。 |
| 覆盖扩展 | 新增充电排程、手动释放、低压电池、维护、拖车、行车记录仪、USB、软件更新、驾驶员档案、钥匙、摄像头、驾驶辅助、冷却液、轮胎修理工具、数据隐私及成都/杭州/海口服务中心等主题。 |
| 新版数据 | `tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`：50 条 ANSWERABLE，`DEV=20`、`TEST=30`，无 `NO_EVIDENCE` 样本。 |
| 审核页 | 已生成 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-authoring-v3.html`（50 条、52 个 Parent Evidence 区块）。 |
| 自动校验 | 通过 `ParentEvidenceManualReviewWriter` 的严格 Loader 和 Parent 存在性校验；未重新跑检索指标，等待新版审核完成后再执行。 |

### RAG-EV2-G313 最新人工 Eval 接续验证（2026-07-26，离线自动验证完成）

| 项目 | 结果 |
|---|---|
| 最新输入 | 以项目负责人手动修改后的 `evaluation_parent_evidence_v2.json` 为唯一输入；当前 `36` 条 Case、`36` 个唯一 Query，全部为 `ANSWERABLE`。仅修正了 4 个重复 `caseId`，未改变用户审核的 Query、Parent、Child 或 Evidence 内容。 |
| RRF 评测 | `EVALUATION_V2_SUCCESS`；结果文件：`docs/testresult/rag/evaluation_parent_evidence_v2_rrf_current.json`。Coverage@1/@2/@3/@4=`0.5278/0.7222/0.7500/0.7778`，MRR=`0.6412`，最大 Evidence=`4868 token`。 |
| Rerank 评测 | `EVALUATION_V2_SUCCESS`；结果文件：`docs/testresult/rag/evaluation_parent_evidence_v2_rerank_current.json`。Coverage@1/@2/@3/@4=`0.5000/0.6111/0.7222/0.7778`，MRR=`0.6065`，最大 Evidence=`4821 token`。当前数据下 RRF 在 @1、@2、@3 和 MRR 优于 Rerank，@4 持平；不能宣称 Rerank 全面提升。 |
| 候选诊断 | 两条链路均记录 `exactDuplicateDropCount=19`、`nearDuplicateDropCount=16`、`parentOccupancyDropCount=163`，说明 RRF 后已经执行统一去重与同 Parent 占位限制。 |
| 未覆盖样本 | RRF 未覆盖 8 条、Rerank 未覆盖 8 条；完整列表和指标对照见 `docs/testresult/rag/parent_evidence_eval_v2_current_report.md`。这些是当前评测结果，不等于 Android 真机链路结论。 |
| Android 验证 | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug -Pkotlin.compiler.execution.strategy=in-process --no-daemon` 通过；`62 actionable tasks`，无测试失败、构建失败或 lint error。 |
| 未完成 Gate | 当前 AVD 仍未形成可用 ADB 设备，`connectedDebugAndroidTest` 仍待设备条件满足；DEV/TEST 阈值校准、置信度校准和正式发布审批仍未关闭。Bundle 继续保持 `TEST_ONLY`，未复制到主 APK。 |

### RAG-EV2-G314 最新 Eval 重测（2026-07-26，离线自动验证完成）

| 项目 | 结果 |
|---|---|
| 最新输入 | 用户更新后的 `evaluation_parent_evidence_v2.json`，共 33 条；原有内容全部保留。发现 4 条新增题目复用了 `lamp-condensation`，仅按题意修正为 `trunk-load-limit`、`interior-lock-unlock`、`wireless-charging-power`、`supercharger-fee-info`，使 33 个 Case ID 唯一。 |
| RRF | Coverage@1/@2/@3/@4=`0.7879/0.9394/0.9394/0.9697`，MRR=`0.8712`，最大 Evidence=`4010 token`；仅 `pre-drive-check` 未覆盖。 |
| Rerank | Coverage@1/@2/@3/@4=`0.8182/0.8485/0.9091/0.9394`，MRR=`0.8611`，最大 Evidence=`4821 token`；未覆盖 `basic-vehicle-warranty`、`software-update`。 |
| 结论 | Rerank 只提升了 Coverage@1，Coverage@2/@3/@4 和 MRR 均低于 RRF；当前 33 条数据不支持宣称 Rerank 整体优于 RRF。 |
| 产物 | `docs/testresult/rag/evaluation_parent_evidence_v2_rrf_latest.json`、`docs/testresult/rag/evaluation_parent_evidence_v2_rerank_latest.json`、`docs/testresult/rag/parent_evidence_eval_v2_latest_report.md`；对比页为 `work/eval-v2-rrf-rerank-latest-review.html`。 |

### RAG-EV2-G315 Phase 4 Android 候选 Bundle 验证（2026-07-27，JVM/APK/Lint 完成，AVD 外部阻塞）

| 项目 | 结果 |
|---|---|
| Test 资产 | `prepareRagTestAssets` 已将 `tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2` 合并到 `app/build/intermediates/assets/debugAndroidTest/mergeDebugAndroidTestAssets/rag/model_y_title_v2_candidate`；未进入 main assets。 |
| Manifest/Scope | 合并资产读取成功：`bundleId=model-y-2026-refresh-trial`、`bundleVersion=TEST_ONLY-model-y-2026-refresh-v4-expanded`、Scope=`model-y-2026-cn-2026-refresh-rwd`。 |
| Hash | 合并资产 `data.mdb` SHA-256=`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`，与源 Bundle 一致。 |
| JVM | 强制重跑 `:app:testDebugUnitTest --rerun-tasks`：`452 tests`、`0 failures/errors`、`0 skipped`。 |
| APK/Lint | `:app:assembleDebug`、`:app:lintDebug` 通过；lint 无 error，保留既有 warning。 |
| AVD | 实际执行 `connectedDebugAndroidTest`，返回 `DeviceException: No connected devices!`；本项未记为通过。 |
| 当前 Gate | Android JVM、APK、Lint、Test 资产和 Hash 校验完成；AVD 7/7 设备测试通过。Bundle 继续 `TEST_ONLY`，不提升 `APPROVED`。 |

### RAG-EV2-G316 Phase 4 AVD 与 Fixture 协议回归（2026-07-27，COMPLETED）

| 项目 | 结果 |
|---|---|
| 首轮失败定位 | AVD 首轮 7 项中 3 项失败：Model Y Bundle 报 `MANIFEST_REQUIRED_FIELD_MISSING`，开发候选报 `STORE_VERSION_MISMATCH`，旧 ObjectBox Fixture 的 BM25 posting 未按 TITLE/BODY 字段协议写入。 |
| 修复 | 使用当前 Manifest V2 Writer 语义补齐 Model Y 候选的 `childMergeMaxTokens`；重新生成 `development-v1/candidate-v1` 和 `objectbox-v1` Fixture；Fixture 倒排改为 TITLE/BODY + 中文 bi/tri-gram，Metadata 同步字段化 BM25 统计。未放宽 Android 严格校验。 |
| 定向回归 | `connectedDebugAndroidTest` 定向执行 4 项，全部通过。 |
| 全量 AVD 回归 | `connectedDebugAndroidTest --rerun-tasks` 在 `Automotive_1408p_landscape(AVD) - 15` 执行 7 项，`7/7 passed`。 |
| 相关测试 | `ModelYV4KnowledgeBundleInstrumentedTest`、`KnowledgeAssetInstallInstrumentedTest`、`ObjectBoxLocalSearchInstrumentedTest`、`ObjectBoxFixtureInstrumentedTest` 及既有 3 项测试均通过。 |
| 离线端回归 | `tools/rag-indexer/gradlew.bat test` 通过；`installDist` 本轮因既有非空安装目录保护而拒绝覆盖，未将该次命令记为通过。此前 `test installDist` 已有通过记录。 |
| 当前 Gate | Phase 4 Android 设备验收关闭；离线 CLI/JVM/APK/Lint/AVD 均通过。人工 Eval、DEV/TEST 冻结、置信度校准和正式发布审批仍是后续 Gate。 |

### RAG-EV2-G317 置信度校准审计（2026-07-27，诊断准备完成）

| 项目 | 结果 |
|---|---|
| 当前分组 | 最新人工数据集共 33 条：DEV 9、TEST 24；沿用当前“先跑通”口径，不扩大解释为正式发布质量证明。 |
| DEV 对照 | RRF：Coverage@1/@2/@4=`0.7778/0.8889/1.0000`、MRR=`0.8611`；Rerank：`0.7778/0.7778/0.8889`、MRR=`0.8148`。 |
| TEST 对照 | RRF：Coverage@1/@2/@4=`0.7917/0.9583/0.9583`、MRR=`0.8750`；Rerank：`0.8333/0.8750/0.9583`、MRR=`0.8785`。TEST 仅作为一次性对照，未用于调参。 |
| 诊断结论 | 已补齐离线报告生成字段并重新生成 `evaluation_parent_evidence_v2_rerank_calibration.json`；DEV 选择 HIGH=`score>=0.94 && margin>=0.05`、MEDIUM=`score>=0.90 && margin>=0.02`，TEST 一次性确认 HIGH 5/5、MEDIUM 8/9、LOW 8/10。RRF 或缺少分数仍保持 `UNASSESSED`。 |
| 产物 | `docs/testresult/rag/parent_evidence_confidence_calibration_v2.md`。 |
| 当前 Gate | Phase 4 和置信度校准已关闭；下一步只做最终 DoD、报告和 README 一致性审计，不修改当前检索参数或发布状态。 |

### RAG-EV2-G318 置信度阈值校准（2026-07-27，COMPLETED）

| 项目 | 结果 |
|---|---|
| DEV 选择 | HIGH `score>=0.94 && margin>=0.05`；MEDIUM `score>=0.90 && margin>=0.02`；其余 Rerank 为 LOW；RRF 不套用该阈值。 |
| TEST 确认 | HIGH 5/5、MEDIUM 8/9、LOW 8/10（一次性确认，未反向调参）。 |
| Android 策略 | 新增 `RetrievalConfidencePolicy`，仅对正常 Rerank Parent 分类；RRF fallback、缺分数或缺间隔均输出 `UNASSESSED`。策略版本 `DEV-CALIBRATED-2026-07-27-1`。 |
| 验证 | Android JVM `454 tests` 通过；Automotive API 35 AVD `7/7` 通过；离线 CLI `test` 通过。 |
| 当前 Gate | Confidence 校准完成，Bundle 仍为 `TEST_ONLY`；进入最终 DoD 审计，未授权正式发布。 |


| 严格 DoD 例外 | 当前数据集没有 `NO_EVIDENCE` 样本，无法产生 No-Evidence 阈值的经验校准；33 条数据规模仍是“先跑通”批准口径，不等同于正式 50 条质量 Gate。相关限制已写入 `parent_evidence_ablation_v2.md`，并在 G320 最终审计中作为批准例外记录。 |

### RAG-EV2-G320 最终 DoD 审计（2026-07-27，COMPLETED）

| 项目 | 结果 |
|---|---|
| 实现范围 | Parent/Child 分块、标题参与 Dense/BM25、RRF、Exact/Near Dedup、Child Rerank、Child→Parent Evidence、Parent 置信度和 Eval V2 均有实现与报告。 |
| 自动化验证 | 离线 CLI `test` 通过；消融 7 模式成功；Android JVM 454 tests、Debug APK、Lint、Automotive AVD 7/7 通过。 |
| 文档一致性 | 08 计划新增最终 DoD 审计；本台账、README、消融报告、置信度报告均记录同一 Bundle Hash、33 条先跑通数据集和 TEST_ONLY 状态。 |
| 批准例外 | 33 条数据集代替正式 50 条质量集；无 `NO_EVIDENCE` 样本因此不锁定经验 No-Evidence 阈值。两项均已明确记录，不伪装为正式质量结论。 |
| 发布边界 | Bundle 仍为 `TEST_ONLY`，未复制到主 APK，未提升 `APPROVED`。 |
| Goal 状态 | 在批准例外范围内，08 计划实现与分阶段验证完成；正式质量扩展、补充 NO_EVIDENCE 样本和发布审批属于后续独立工作。 |

### RAG-EV2-G319 检索消融矩阵（2026-07-27，COMPLETED）

| 项目 | 结果 |
|---|---|
| 执行入口 | 新增 `evaluate-v2-ablation`，固定同一 Bundle、同一 33 条数据集、同一 Embedding/候选预算，依次执行 Dense-only、BM25-only、RRF raw、Exact Dedup、Exact/Near Dedup、Rerank、Rerank fallback。 |
| 结果产物 | `docs/testresult/rag/evaluation_parent_evidence_v2_ablation_v2.json`；汇总说明见 `docs/testresult/rag/parent_evidence_ablation_v2.md`。 |
| 关键结论 | BM25-only 当前 MRR=`0.9141`；RRF+去重 MRR=`0.8712`；Rerank MRR=`0.8611`，只提升 Coverage@1，不支持“Rerank 全面优于 RRF”。 |
| 去重结论 | Exact Drop=`13`、Near Drop=`9`、Parent Occupancy Drop=`147`；Exact/Near 去重相较 raw RRF 提升 Coverage@4 与 MRR。 |
| Fallback | Rerank fallback 与 RRF+Dedup 指标一致，证明失败时保留本地链路。 |
| 验证 | 离线 CLI `test` 通过；消融命令 `EVALUATION_V2_ABLATION_SUCCESS modes=7 cases=33`。 |
| 当前 Gate | 消融矩阵关闭；进入最终 DoD 审计。样本规模继续按已批准的 33 条“先跑通”口径，不伪装成正式 50 条质量集。 |
