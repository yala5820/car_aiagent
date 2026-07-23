# 车辆知识 RAG 离线构建端详细实施计划

> 文档状态：已完成执行前复盘，待执行  
> 目标工程：`AIAgent/tools/rag-indexer`  
> 工程形态：同一 Git 仓库内、独立 Gradle 构建的 Java 17 桌面 JVM CLI  
> 上位规范：`docs/plan_overall/rag/vehicle_agent_rag_design.md`  
> 配套计划：`docs/plan_overall/rag/vehicle_agent_android_rag_plan.md`  
> 计划执行方式：由离线构建端子 Agent 以 Goal 模式按 Phase、Task 顺序实施  
> 最后更新：2026-07-21

> V2 覆盖说明（2026-07-24）：本文件保留为既有实施历史。涉及 Chunk、Child Rerank、Parent Context/Evidence、去重、Token 预算和 Eval 的 V1 描述，均由 `docs/plan_overall/eval/08-rag-parent-child-retrieval-eval-v2-implementation-plan.md` 与修订后的总体设计覆盖；执行 V2 不得同时采用旧规则。

执行前复盘已修正：与 Android 计划的统一执行顺序、共享文件唯一负责人、跨端 Manifest/Scope/Embedding/Analyzer/Fingerprint Golden、兼容 Fixture 归属、Work 中间正文安全、PDF Action/附件边界、HTML Selector 资源边界、同文件系统原子发布、禁止覆盖旧 Output，以及可重复的离线 Dense 评测入口。

---

## 1. 文档目的

本文将已批准的车辆知识 RAG 总体设计拆解为离线构建端可执行的文件级计划。最终实现应把人工审核的 PDF、本地静态 HTML 和 Markdown 文档，转换为 Android 可以直接安装和查询的预构建 ObjectBox 数据库。

执行者必须能够从每个 Task 中明确：

- 本 Task 要解决什么问题；
- 依赖哪些前置产物；
- 需要创建或修改哪些文件；
- 每个文件承担什么职责；
- 核心实现步骤和错误语义是什么；
- 哪些工作不属于本 Task；
- 应运行哪些测试；
- 满足哪些条件才可以进入下一个 Task 或 Phase。

本文只规划离线构建端。Android 的 Store 安装、Runtime 路由、ToolLoop、CitationGuard、Memory 和运行时 Hybrid Retrieval 由 Android 端计划负责。两端通过共享 ObjectBox Schema、Manifest、Embedding、Lexical Analyzer、Metadata、SourceLocator 和兼容门禁协作。

---

## 2. 已批准的工程组织方式

### 2.1 Monorepo 边界

离线构建工具放在当前 `AIAgent` Git 仓库中，但不是 Android 子模块：

```text
AIAgent/
├── app/                              # Android Agent，现有 Gradle Build
├── rag-schema/                       # ObjectBox Schema 唯一规范源
├── tools/
│   └── rag-indexer/                  # 独立 Java 17 Gradle CLI Build
│       ├── settings.gradle.kts
│       ├── build.gradle.kts
│       ├── gradlew / gradlew.bat
│       ├── gradle/
│       └── src/
└── docs/
```

必须保持以下隔离：

- Android 根 `settings.gradle.kts` 不执行 `include(":tools:rag-indexer")`。
- CLI 拥有自己的 `settings.gradle.kts`、Version Catalog、Wrapper 和 `application` 入口。
- CLI 使用 Java 17，不依赖 Android SDK、Android Context 或 AAR。
- PDFBox、Tabula、HTML Parser、Markdown Parser 等只存在于 CLI 依赖中，不进入 APK。
- CLI 不进入 Agent 请求链，也不在车机运行。
- 两端只共享 `rag-schema`、版本化测试向量和最终交付协议。
- 已废弃的外部空目录 `D:\code\android\AndroidStudioProjects\AIAgent_RAG` 不属于实施范围；计划执行者不得向其中生成代码或产物。

### 2.2 独立构建入口

CLI 构建和运行统一从其自身目录执行：

```powershell
cd tools\rag-indexer
.\gradlew.bat clean test
.\gradlew.bat installDist
.\gradlew.bat run --args="--help"
```

Android 构建继续使用仓库根 Wrapper。两个 Wrapper 可以使用同一已验证 Gradle 基线，但它们的 Settings、依赖和任务图相互独立。

---

## 3. V1 工作范围

### 3.1 必须完成

1. 创建可分发、可重复运行的 Java 17 CLI。
2. 使用版本化 `corpus.json` 描述 Bundle、单一 Knowledge Scope、Document Metadata 和格式专属配置。
3. 使用独立版本化构建配置描述安全限制、Parser、Chunk、Embedding、HNSW 和质量门禁。
4. 对输入路径、文件类型、字符集、大小、符号链接和格式声明做确定性安全校验。
5. 通过统一 `DocumentParserRegistry` 支持：
   - PDFBox 文本/版面解析；
   - Tabula 文本型 PDF 表格；
   - 本地静态 HTML DOM；
   - CommonMark + GFM Table。
6. 把三种格式归一化为统一 `StructuredBlock`、`TableBlock` 和 `SourceLocator`。
7. 生成 Heading-aware Parent/Child Chunk，保留 Warning、限制、表头和可靠 Locator。
8. 使用稳定 SHA-256 生成 Document/Parent/Child 等持久标识。
9. 使用 DashScope `text-embedding-v4` 为每个 Child 生成 1024 维向量。
10. 使用与 Android 一致的中文 bigram/trigram + Latin/故障码 Analyzer 构建 postings。
11. 使用共享 Entity 和 ObjectBox Meta Model 写入 `data.mdb`，HNSW 使用 Cosine。
12. 生成可被 Android 校验的 `manifest.json` 和审计用 `build-report.json`。
13. 实现完整构建、仅校验输入、验证已有 Bundle 三类生产工作流，并提供只读离线 Dense 评测工作流。
14. 任何硬失败均不得发布部分数据库；正式输出必须采用 staging 和原子发布。
15. 建立 PDF、静态 HTML、Markdown、安全攻击样本和跨格式一致性测试。
16. 与 Android 端执行真实 `data.mdb` 跨端打开、Dense/Lexical/Scope/Locator 门禁。

### 3.2 明确不做

- 不开发 Android 运行时代码。
- 不实现 Query Embedding、Rerank 或 Agent 回答；CLI 只计算文档 Child Embedding。
- 不实现 Web 抓取、URL 输入、链接递归、远程资源下载或动态网页渲染。
- 不启动 Chromium、WebView、Playwright、Selenium 等浏览器内核。
- 不执行 JavaScript、WebAssembly、HTML 事件处理器或 Markdown 内嵌脚本。
- 不读取 HTML/Markdown 引用的本地或远程图片、CSS、字体、iframe、音视频。
- 不实现 OCR、扫描版 PDF、图片表格或流程图视觉理解。
- 不支持压缩包、目录自动扫描入库或未在 `corpus.json` 人工声明的文件。
- 不根据文件名、扩展名、Front Matter 或模型推断车型、版本、地区和 Document Type。
- 不在同一个 Store 混放多个 `knowledgeScopeId`。
- 不提供修改 ObjectBox 数据库的交互式控制台。
- 不把 `build-report.json` 打入 APK。
- 不在日志、缓存、报告或产物中写入 DashScope API Key。

### 3.3 必须通过验证后才能锁定的值

以下内容不得在计划执行时凭经验伪造：

- ObjectBox Runtime/Plugin/Vector Search 版本和最终 HNSW 参数；
- PDFBox、Tabula、静态 HTML Parser、Markdown Parser 及 JSON/CLI 库版本；
- 各依赖许可证和商业发布结论；
- DashScope 单批最大输入数、文本限制、速率限制和重试策略上限；
- 单文件、总语料、DOM/AST 节点、表格单元格和文本异常比例正式阈值；
- Parent/Child Token 大小、Overlap、Parent 上限和表格分片行数；
- Heading/Warning 版面启发式的正式阈值；
- PDF 疑似扫描页判定阈值；
- Embedding 并发、批大小和请求间隔；
- 正式 HNSW 性能/体积参数。

Phase 0 可以确定版本和兼容参数；真实文档相关阈值必须在 Phase 5 评测后锁定。测试 Fixture 可以使用明确标记为 TEST_ONLY 的数值，但不得直接复制为生产配置。

---

## 4. Goal 模式执行规范

### 4.1 Goal 粒度

- 每个 Task 对应一个独立 Goal。
- 默认按编号串行执行；只有文件集合完全不重叠且上游协议已经锁定时才允许并行。
- 一个 Phase 的测试门禁未通过，后续 Phase 不得开始。
- Phase 0 的许可证和跨端兼容未通过，正式依赖接入不得继续。
- 子 Agent 不得把“写了类骨架”视为完成；Task 验收项必须有测试或可重复证据。

### 4.2 每个 Goal 的固定过程

1. 阅读总体设计、本计划及本 Goal 依赖的上游产物。
2. 执行 `git status --short`，保留用户已有修改。
3. 说明现状、预设前提和本 Goal 文件边界。
4. 按现有项目中文注释规范实现最小完整能力。
5. 先运行定向测试，再运行 Phase 规定的回归测试。
6. 检查产物确定性、敏感信息、绝对路径泄漏和临时文件清理。
7. 汇总修改文件、验证结果和未解决风险，再标记 Goal 完成。

### 4.3 必须暂停并询问的情况

- 任一依赖的许可证、商业使用或再分发条件无法确认。
- ObjectBox CLI 与 Android 无法消费同一 Meta Model 或打开同一数据库。
- ObjectBox 插件无法支持共享源码，也无法通过确定性同步可靠物化 Schema。
- ObjectBox 实际 Store 不是可作为单个 `data.mdb` 交付，或还需要总体协议未定义的必要文件。
- Parser 依赖冲突导致三种格式不能在同一 CLI 进程运行。
- PDF 表格或阅读顺序需要引入 OCR/商业 SDK 才能达到目标。
- 静态 HTML 在不执行脚本时没有正文，而业务要求仍必须支持。
- 真实文档需要 CommonMark/GFM Table 之外的新 Markdown 方言。
- 源文档许可证不允许进入仓库、测试 Fixture、云 Embedding 或发布产物。
- DashScope Embedding 实际 API 与 1024 维协议不兼容。
- 真实文档质量不足但项目方要求绕过硬门禁发布。
- Bundle 需要容纳多个独立车型 Scope。
- 需要改变 SourceLocator、Entity 字段、Manifest 或 Analyzer 协议。

### 4.4 统一验证命令

计划实施后，CLI 侧基础命令为：

```powershell
cd tools\rag-indexer
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat installDist
.\gradlew.bat distZip
```

跨端门禁还需回到仓库根执行 Android 计划规定的单元/Instrumentation 测试。任何命令未执行时必须明确标记“未执行”，不能写成“通过”。

---

## 5. 目标目录与职责

```text
AIAgent/
├── rag-schema/
│   ├── src/main/java/com/hirain/aiagent/rag/store/entity/
│   ├── objectbox-models/default.json
│   ├── contracts/
│   │   └── knowledge-bundle-manifest.schema.json
│   ├── test-vectors/
│   │   ├── lexical-analyzer-v1.json
│   │   ├── embedding-input-v1.json
│   │   ├── knowledge-scope-v1.json
│   │   ├── source-locator-v1.json
│   │   └── compatibility-fingerprints-v1.json
│   └── test-fixtures/objectbox-v1/
│       ├── data.mdb
│       ├── manifest.json
│       └── fixture-report.json
│
└── tools/rag-indexer/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradlew / gradlew.bat
    ├── gradle/
    │   ├── libs.versions.toml
    │   └── wrapper/
    ├── README.md
    ├── schemas/
    │   ├── corpus.schema.json
    │   ├── rag-build.schema.json
    │   └── retrieval-evaluation.schema.json
    ├── examples/
    │   ├── corpus.example.json
    │   └── rag-build.example.json
    └── src/
        ├── main/java/com/hirain/aiagent/rag/indexer/
        │   ├── RagIndexerMain.java
        │   ├── cli/
        │   ├── config/
        │   ├── corpus/
        │   ├── model/
        │   ├── parser/
        │   │   ├── pdf/
        │   │   ├── html/
        │   │   ├── markdown/
        │   │   └── table/
        │   ├── chunk/
        │   ├── embedding/
        │   ├── lexical/
        │   ├── store/
        │   ├── artifact/
        │   ├── report/
        │   ├── pipeline/
        │   └── util/
        └── test/
            ├── java/...
            └── resources/fixtures/...
```

### 5.1 依赖方向

```text
CLI Command
    ↓
BuildPipeline / ValidatePipeline / VerifyPipeline
    ↓
Corpus + Config + Security Validator
    ↓
Parser Registry → StructuredBlock
    ↓
Chunker → Child/Parent
    ↓
Embedding + Lexical Index
    ↓
ObjectBox Writer
    ↓
Manifest + Build Report + Artifact Publisher
```

禁止反向依赖：Parser 不调用 Chunker；Chunker 不调用 ObjectBox；Entity 不依赖 Parser；Report 不决定业务结果；CLI Command 不直接操作 PDFBox/Tabula。

---

## 6. CLI 外部接口

### 6.1 V1 子命令

```text
rag-indexer validate --corpus <corpus.json> --config <rag-build.json> --work-dir <dir>
rag-indexer build    --corpus <corpus.json> --config <rag-build.json> --output <dir> --work-dir <dir>
rag-indexer verify   --bundle <output-dir>  --config <rag-build.json>
rag-indexer evaluate --bundle <output-dir>  --dataset <evaluation.json> --report <file>
rag-indexer --help
rag-indexer --version
```

- `validate`：校验配置、路径、格式、Parser 和结构质量，不调用 Embedding、不写正式 Store。
- `build`：执行完整解析、Chunk、Embedding、Lexical、ObjectBox 和交付流程。
- `verify`：独立重新验证现有 `data.mdb`、Manifest、Metadata、计数、Hash 和最小查询。
- `evaluate`：只读评估候选 Bundle 的 Dense Recall@K/MRR 等离线指标，不修改 Store；完整 Hybrid/Rerank/回答评测仍由 Android 端负责。
- `--work-dir`：保存可恢复的中间状态和 Embedding Cache；不得与正式输出目录相同。
- CLI Flag 只选择文件和工作目录，不允许临时覆盖车型、维度、Top-K、Parser 安全边界或发布阈值。

Corpus Root、Work Dir 和 Output/Bundledir 必须是真实路径层面互不相同且不存在祖先/子目录重叠；输出父目录和 Work 目录的符号链接同样需要解析后检查。`validate` 及失败的 `build` 把报告写入 `work/runs/<runId>/`，只有最终成功且 `publishable=true` 的 Build Report 才随 Bundle 发布到 Output。

Work 目录可能包含解析后的官方正文、Chunk 和向量，必须按敏感构建中间产物处理：默认不进入 Git/分发包，README 说明访问权限、保留周期和安全清理方式；报告和控制台不得输出 Work 的绝对路径。Embedding Cache 只保存向量与校验元数据，但 Parse/Chunk Checkpoint 仍可能包含正文。

### 6.2 退出码

| Exit Code | 含义 |
|---:|---|
| 0 | 成功，且对应命令全部门禁通过 |
| 2 | CLI 参数或配置 Schema 错误 |
| 3 | Corpus/输入安全/解析硬失败 |
| 4 | Embedding 云调用最终失败 |
| 5 | Chunk/Lexical/ObjectBox 构建失败 |
| 6 | Manifest、产物或跨端兼容验证失败 |
| 7 | 产物不可发布，仅生成失败报告 |
| 130 | 用户中断或进程取消 |

Java 异常堆栈只在显式 Debug 日志中出现；默认控制台输出稳定原因码、相对文件标识和报告路径。

### 6.3 环境变量

- `DASHSCOPE_API_KEY`：唯一允许读取的 Embedding 凭证来源；不得写入配置样例。
- `SOURCE_DATE_EPOCH`：可选，用于 CI 可重复构建的 `builtAtEpochMs`；未设置时使用当前时间。

API Key 不允许通过 `corpus.json`、`rag-build.json` 或命令行参数传入，避免进入 Shell 历史和构建产物。

---

## 7. Phase 总览

| Phase | 目标 | 主要产物 |
|---|---|---|
| Phase 0 | 工程骨架、依赖/许可证和跨端 ObjectBox 最小门禁 | 独立 CLI Build、共享 Schema、兼容报告 |
| Phase 1 | CLI、配置、Corpus、安全输入与统一领域协议 | 可严格验证的构建输入和 Parser Registry |
| Phase 2 | PDF、静态 HTML、Markdown 解析与结构恢复 | 统一 StructuredBlock/TableBlock/质量诊断 |
| Phase 3 | Chunk、Embedding、Analyzer 与 postings | 完整 Parent/Child、向量和 Lexical 索引数据 |
| Phase 4 | ObjectBox、Manifest、Report 与原子产物 | `data.mdb`、`manifest.json`、`build-report.json` |
| Phase 5 | 全链路 Fixture、真实文档评测和 Android 联合门禁 | 可发布候选与审计报告 |

硬依赖顺序：

```text
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5
```

### 7.1 两份计划的统一执行顺序与共享所有权

离线计划是共享 Schema、兼容 Fixture 和正式 Bundle 的事实负责人，但不能独自宣布共同 Phase 0 或最终 Phase 5 完成。统一顺序为：

1. 离线 Task 0.1/0.2 创建 CLI 和共同依赖审查记录；
2. 离线 Task 0.3 创建 `rag-schema` 唯一规范源；
3. 离线 Task 0.4 生成最小兼容 Fixture；
4. Android Task 0.1～0.3 消费 Schema/Fixture，并补齐目标 ABI、APK 和打开查询验证；
5. 共同 Phase 0 通过后，离线 Phase 1～4 先生成开发 Bundle；
6. Android Phase 1～4 使用该 Bundle 完成运行链；
7. 离线 Phase 5 生成正式候选，最后两端联合完成 Phase 5。

共享文件所有权固定为：

| 共享产物 | 写入负责人 | 另一端行为 |
|---|---|---|
| `rag-schema/**` | 离线 Task 0.3 | Android 只消费、验证和提出变更 |
| `rag_dependency_compatibility_gate.md` | 离线 Task 0.2 首建 | Android 补充运行时/ABI 结论 |
| ObjectBox 兼容 Fixture | 离线 Task 0.4 | Android 通过 generated test assets 消费 |
| 正式 Bundle | 离线 Phase 4/5 | Android 安装、检索和发布验收 |

任何共享协议变更都必须先修改规范源、提升相应版本、重新生成 Fixture，再执行 Android 验证；禁止两个子 Agent 同时编辑同一共享文件。

---

# Phase 0：工程、依赖、许可证与跨端兼容门禁

## 目标

在编写正式 Parser 和建库逻辑前，建立独立 JVM 工程，并证明所有候选依赖可共存、可发布，ObjectBox 预构建数据库能由 Android 端使用同一 Schema 打开。

## Task 0.1：创建独立 Java 17 CLI 工程骨架

### 创建文件

- `tools/rag-indexer/settings.gradle.kts`
- `tools/rag-indexer/build.gradle.kts`
- `tools/rag-indexer/gradle.properties`
- `tools/rag-indexer/gradle/libs.versions.toml`
- `tools/rag-indexer/gradlew`
- `tools/rag-indexer/gradlew.bat`
- `tools/rag-indexer/gradle/wrapper/*`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/RagIndexerMain.java`
- `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/RagIndexerMainTest.java`
- `tools/rag-indexer/README.md`

### 修改文件

- 仓库根 `.gitignore`：明确忽略 CLI 的 `.gradle/`、`build/`、`work/`、本地 `output/` 和临时凭证文件；不得用过宽规则忽略正式源码或 Schema。

### 实现要求

1. 使用 Gradle `java`、`application`、`distribution` 能力和 Java Toolchain 17。
2. CLI `group`/包名固定在 `com.hirain.aiagent.rag.indexer`，避免与 Android Runtime 混淆。
3. 使用独立 Settings，不修改 Android 根 `settings.gradle.kts` 的模块列表。
4. Wrapper 版本先与仓库当前 Gradle 8.11.1 对齐，除非 Phase 0 兼容验证证明必须调整。
5. `RagIndexerMain` 初期只输出帮助/版本和受控“未实现”错误，不加入 Parser 依赖。
6. `installDist` 和 `distZip` 产出可在只有 Java 17 的桌面环境运行的脚本/分发包。
7. README 明确两个 Gradle Build 的运行目录，防止从仓库根误执行 CLI 任务。

### 边界

- 不把 CLI include 到 Android 构建。
- 不创建 Android Library 共享模块。
- 本 Task 不引入 ObjectBox/PDFBox/Parser 正式依赖。

### 验收

- `gradlew.bat test`、`installDist`、`--help`、`--version` 成功。
- Android 根 `projects` 输出仍只包含 `:app`。
- CLI 分发包不包含 Android SDK/AAR。

## Task 0.2：建立依赖、许可证与安全审查门禁

### 创建文件

- `docs/plan_overall/rag/rag_dependency_compatibility_gate.md`：与 Android 端共同维护，不重复创建第二份结论。
- `tools/rag-indexer/dependency-license-report/README.md`

### 审查候选

- ObjectBox Java Runtime、Gradle Plugin、Vector Search。
- Apache PDFBox。
- Tabula Java 及其传递依赖。
- 静态 HTML Parser 候选。
- CommonMark Parser 与 GFM Table 扩展候选。
- JSON 序列化/配置校验库。
- CLI 参数库；若引入成本不合理，保留小型手写参数层。
- OkHttp/HTTP Client 与测试 Mock Server。
- 测试框架及必要的断言库。

### 工作步骤

1. 从受信 Maven 仓库解析准确依赖树。
2. 记录直接/传递版本、许可证、项目主页、仓库来源和已知发布限制。
3. 确认 ObjectBox 的 CLI、Android、Plugin、原生库和 Vector Search 使用方式符合项目发布方式。
4. 验证所有 Parser 可在同一 Java 17 进程加载，无冲突的 PDFBox 版本或日志实现。
5. 进行依赖漏洞扫描；工具不可用时记录未执行和替代人工检查。
6. 项目负责人明确签署法律/发布结论，子 Agent 不替代法律判断。
7. 只有通过后才把锁定版本写入 CLI Version Catalog。

### 停止条件

任一关键依赖许可证或商业发布条件不清楚时，Phase 0 不得通过；禁止先编码后补许可。

## Task 0.3：创建共享 ObjectBox Schema 唯一规范源

### 创建文件

- `rag-schema/src/main/java/com/hirain/aiagent/rag/store/entity/KnowledgeStoreMetadataEntity.java`
- `rag-schema/src/main/java/com/hirain/aiagent/rag/store/entity/KnowledgeDocumentEntity.java`
- `rag-schema/src/main/java/com/hirain/aiagent/rag/store/entity/KnowledgeChunkEntity.java`
- `rag-schema/src/main/java/com/hirain/aiagent/rag/store/entity/LexicalTermEntity.java`
- `rag-schema/objectbox-models/default.json`
- `rag-schema/README.md`
- `rag-schema/contracts/knowledge-bundle-manifest.schema.json`
- `rag-schema/test-vectors/embedding-input-v1.json`
- `rag-schema/test-vectors/knowledge-scope-v1.json`
- `rag-schema/test-vectors/source-locator-v1.json`
- `rag-schema/test-vectors/compatibility-fingerprints-v1.json`

### 修改文件

- `tools/rag-indexer/build.gradle.kts`
- Android `app/build.gradle.kts` 的修改由 Android 计划对应 Goal 执行；本 Goal只协调，不越权替代 Android 实现。

### 实现要求

1. 四个 Entity 字段、类型、唯一索引、1024 维 HNSW Cosine 与总体设计第 10 节一致。
2. Parent 的 Embedding 允许为空；Child 必须有效。
3. HTML/Markdown 不适用的数值 Locator 字段落库为 0，映射层必须恢复为空语义。
4. `default.json` 的 Entity/Property ID 和 UID 纳入 Git，禁止删除后重建。
5. 先实测两个 Build 通过外部 `sourceSets` 直接消费 `rag-schema`；失败时才实现确定性同步任务。
6. 若物化，生成目录禁止人工编辑，构建任务应在编译前校验源码和 Meta Model Hash。
7. `schemaFingerprint` 使用规范化文件内容的 SHA-256，算法和输入文件集合写入 README。
8. Manifest JSON Schema、Scope ID、Embedding 输入、SourceLocator、Schema/HNSW/Parser/Chunk 指纹规范集中放在共享目录；Android 与 CLI 必须运行同一 Golden。

### 测试

- Schema Hash 稳定性。
- Entity 字段反射/生成模型契约。
- 人工改动物化副本时构建失败。
- 删除/重生成 UID 的保护性检查。

## Task 0.4：完成最小跨端数据库兼容 Spike

### 创建文件

- `tools/rag-indexer/src/test/java/.../store/ObjectBoxCrossRuntimeSpikeTest.java`
- `tools/rag-indexer/src/test/resources/fixtures/compatibility/README.md`
- `rag-schema/test-fixtures/objectbox-v1/data.mdb`
- `rag-schema/test-fixtures/objectbox-v1/manifest.json`
- `rag-schema/test-fixtures/objectbox-v1/fixture-report.json`
- Android 端只创建 generated test asset 复制任务和 Instrumentation，不人工维护第二份 Fixture。
- 更新 `rag_dependency_compatibility_gate.md` 的技术验证部分。

### 工作步骤

1. CLI 使用共享 Entity 写入单 Scope 最小数据库。
2. 写入三种 SourceFormat 的 Document/Chunk、Parent/Child、向量和一条 Lexical posting。
3. 关闭 Store 后只提取协议允许的交付文件。
4. Android 使用同一 Meta Model 打开，并执行 Dense、Lexical、Scope/Metadata、SourceLocator 查询。
5. 验证错误 Schema Fingerprint、HNSW 指纹和 Scope 被 Android 拒绝。
6. 验证目标 ABI 原生库加载和 APK/AAB 打包。
7. 确认正式交付能稳定归结为总体协议中的 `data.mdb`；若 ObjectBox 需要其他不可省略文件，必须暂停修订总体协议。
8. 记录 ObjectBox 打开是否会产生内部维护写入；业务数据仍保持只读语义。
9. Fixture 必须记录生成命令、CLI Commit、Schema/HNSW 指纹和受控合成数据来源；Schema 变化后旧 Fixture 不得静默沿用。

### Phase 0 测试门禁

- CLI `test`、`check`、`installDist` 通过。
- Android 最小打开与查询 Instrumentation 通过。
- Parser 共存 Smoke Test 通过。
- 许可证/发布审查和技术兼容报告均为通过。
- CLI 与 Android 使用同一 Schema、UID、ObjectBox 版本和 HNSW 配置。

Phase 0 未全部通过，不得开始 Phase 1 正式功能实现。

---

# Phase 1：CLI、配置、Corpus、安全输入与统一领域协议

## 目标

建立严格、可审计、无云调用的输入层。Phase 1 结束时，CLI 能安全读取并验证 `corpus.json`/构建配置，识别所有声明文档，输出结构化诊断，但尚不要求完成正式解析和建库。

## Task 1.1：实现 CLI 命令、退出码与运行上下文

### 创建文件

`tools/rag-indexer/src/main/java/.../cli/`：

- `RagIndexerCommand.java`：根命令。
- `ValidateCommand.java`：输入/Parser 验证命令。
- `BuildCommand.java`：完整构建命令。
- `VerifyCommand.java`：产物验证命令。
- `EvaluateCommand.java`：候选 Bundle 的只读 Dense 检索评测命令。
- `CliArguments.java`：公共路径参数值对象。
- `CliExitCode.java`：稳定退出码。
- `CliExceptionMapper.java`：异常到退出码/受控消息映射。

`.../pipeline/`：

- `BuildExecutionContext.java`：runId、开始时间、工作目录、取消标记和路径。
- `BuildCancellationToken.java`：Shutdown Hook/中断传播。

### 实现要求

1. 参数解析错误不打印 Java 堆栈。
2. 输入、work、output 路径在命令入口规范化，但安全边界由专门 Validator 判断。
3. 捕获 Ctrl+C/线程中断，停止新批次、关闭 HTTP/Store、保留可恢复 Cache，不发布部分产物。
4. 默认日志不输出 API Key、完整文档或绝对本机路径；报告内部使用 corpus 相对路径。
5. `--version` 从构建属性读取，不在代码重复硬编码。

### 测试文件

- `CliCommandTest.java`
- `CliExitCodeTest.java`
- `BuildCancellationTokenTest.java`

## Task 1.2：定义 `corpus.json` 与构建配置 Schema

### 创建文件

- `tools/rag-indexer/schemas/corpus.schema.json`
- `tools/rag-indexer/schemas/rag-build.schema.json`
- `tools/rag-indexer/examples/corpus.example.json`
- `tools/rag-indexer/examples/rag-build.example.json`

`.../config/`：

- `RagBuildConfig.java`
- `ParserLimitsConfig.java`
- `PdfParserConfig.java`
- `HtmlParserConfig.java`
- `MarkdownParserConfig.java`
- `ChunkingConfig.java`
- `EmbeddingBuildConfig.java`
- `ObjectBoxBuildConfig.java`
- `QualityGateConfig.java`
- `ConfigLoader.java`
- `ConfigValidator.java`
- `ConfigFingerprint.java`

`.../corpus/`：

- `CorpusDefinition.java`
- `BundleDefinition.java`
- `KnowledgeScopeDefinition.java`
- `KnowledgeScopeIdGenerator.java`
- `CorpusDocumentDefinition.java`
- `PdfDocumentOptions.java`
- `PdfExcludedPage.java`
- `HtmlDocumentOptions.java`
- `MarkdownDocumentOptions.java`
- `CorpusLoader.java`
- `CorpusValidator.java`

### `corpus.json` 必备字段

- `schemaVersion`。
- `bundleId`、`bundleVersion`。
- 唯一 `knowledgeScopeId` 和五项 Scope 字段；ID 是对确定性 Scope 规则的显式校验值，不是任意人工字符串。
- Documents 数组，每项包含稳定 ID、标题、类型、版本、语言、SourceFormat、相对路径、SHA-256 期望值和五项适用 Metadata。
- HTML/Markdown 可选显式 Charset。
- PDF 页表格策略和人工批准排除页：物理页、原因、审核状态、审核记录/人、审核时间。
- HTML 可选内容根 Selector、排除 Selector。
- 不允许从 YAML Front Matter 或文件名补齐 Metadata。

### `rag-build.json` 必备字段

- Parser 版本化配置和资源限制。
- HTML 安全模式固定 `STATIC_DOM_ONLY`。
- Markdown 固定 CommonMark + GFM Table。
- Chunk configVersion、Parent/Child/Overlap/Table 分片配置。
- Embedding Provider/Model/Dimension/Template Version 和批处理策略。
- HNSW 配置与指纹规则。
- Analyzer 版本和算法名。
- Quality Gate 阈值及其状态：`TEST_ONLY / APPROVED`。
- Build 阶段不能通过 CLI Flag 临时覆盖这些协议字段。

### 校验要求

1. JSON Syntax、Schema 和语义分层报错。
2. 拒绝未知关键字段，防止拼写错误被静默忽略。
3. 文档 ID 唯一；Bundle 只允许一个 Scope。
4. `KnowledgeScopeIdGenerator` 与 Android Resolver 遵守 `rag-schema/test-vectors/knowledge-scope-v1.json`；Corpus 声明 ID 不等于五项 Scope 计算结果时硬失败。
5. 文档 Metadata 必须等于 Bundle Scope 或经审核声明为 `*`；跨 Scope 具体值硬失败。
6. `bundleVersion` 由发布流程明确提供，内容变化却复用版本应由历史产物比较门禁拒绝。
7. 配置 Hash 使用规范化、确定性 JSON，Map Key 排序，不依赖运行时迭代顺序。
8. Example 中未验证值必须显式为占位或 TEST_ONLY，不能看起来像生产默认值。

### 测试文件

- `ConfigLoaderTest.java`
- `ConfigFingerprintTest.java`
- `CorpusLoaderTest.java`
- `CorpusValidatorTest.java`
- `KnowledgeScopeIdGeneratorGoldenTest.java`
- JSON Schema 示例正反例测试。

## Task 1.3：实现安全输入解析与格式探测

### 创建文件

`.../corpus/`：

- `CorpusPathResolver.java`
- `SourceFileDescriptor.java`
- `SourceFormatDetector.java`
- `SourceCharsetResolver.java`
- `CorpusResourceBudget.java`
- `CorpusSecurityValidator.java`

`.../util/`：

- `Sha256.java`
- `UnicodeTextNormalizer.java`
- `DeterministicJson.java`
- `SafeFileIo.java`

### 实现步骤

1. Corpus 根目录取 `corpus.json` 所在目录或显式 root；Document 只允许相对普通文件路径。
2. `toRealPath()` 后检查文件仍在 Corpus Root 内。
3. 拒绝目录、设备、管道、Socket、归档、URL、UNC/协议伪装和 NUL 字符路径。
4. 符号链接即使声明路径在 Root 内，只要真实目标逃逸也必须拒绝。
5. 校验扩展名、Magic/内容特征和声明 `sourceFormat` 一致。
6. 校验单文件、总语料数量/大小限制；超过限制在打开 Parser 前失败。
7. 流式计算源文件 SHA-256，与人工配置比对；报告只保存相对路径。
8. HTML/Markdown Charset 按“显式配置 → BOM → HTML meta → UTF-8”规则解析；显式值冲突硬失败。
9. PDF 作为二进制交给 PDF 分类器，不做字符集猜测。

### 测试文件

- `CorpusPathResolverTest.java`
- `CorpusSecurityValidatorTest.java`
- `SourceFormatDetectorTest.java`
- `SourceCharsetResolverTest.java`
- 覆盖 `..`、绝对路径、符号链接逃逸、格式伪装、超限和 Hash 不匹配。

## Task 1.4：实现统一领域模型、诊断和 Parser Registry

### 创建文件

`.../model/`：

- `SourceFormat.java`
- `SourceDocument.java`
- `DocumentMetadata.java`
- `SourceLocator.java`
- `StructuredBlock.java`
- `BlockType.java`
- `TableBlock.java`
- `TableExtractionMode.java`
- `BoundingBox.java`
- `ExtractionConfidence.java`
- `ParseResult.java`
- `ParseDiagnostic.java`
- `DiagnosticSeverity.java`
- `BuildReasonCode.java`

`.../parser/`：

- `DocumentParser.java`
- `DocumentParserRegistry.java`
- `ParserContext.java`
- `ParserResourceGuard.java`

### 实现要求

1. 模型不可变、集合防御性复制、字段构造时验证。
2. `SourceLocator` 完整表达 PDF、HTML、Markdown 格式互斥字段。
3. `StructuredBlock.headingPath` 与 `SourceLocator.headingPath` 共享同一结构栈输出，不允许分别推断。
4. `ParseDiagnostic` 含原因码、Severity、Document ID、SourceLocator 和简洁说明；不含异常堆栈。
5. Registry 只按已经校验的 SourceFormat 选择 Parser，不根据扩展名兜底。
6. Parser 只产出 Block/Diagnostic，不做 Chunk、Embedding、ObjectBox。
7. 全部总体设计错误码区分 ERROR/WARNING；新增格式不能降低 PDF 既有硬失败。

`BuildReasonCode` 至少显式包含并测试以下跨阶段稳定码：

- 输入：`SOURCE_FORMAT_MISMATCH`、`SOURCE_PATH_OUTSIDE_CORPUS`、`SOURCE_SIZE_LIMIT_EXCEEDED`、`SOURCE_HASH_MISMATCH`、`SOURCE_CHARSET_UNSUPPORTED`；
- PDF：`MIXED_PDF_REVIEW_REQUIRED`、`SCANNED_PDF_UNSUPPORTED`、`ENCRYPTED_PDF_UNSUPPORTED`（需要密码或禁止内容提取）、`INVALID_PDF`、`PDF_EXCLUDED_PAGE_NOT_APPROVED`；
- HTML：`HTML_CHARSET_CONFLICT`、`HTML_CONTENT_EMPTY`、`DYNAMIC_HTML_UNSUPPORTED`、`HTML_SELECTOR_INVALID`；
- Markdown：`MARKDOWN_CONTENT_EMPTY`、`UNSUPPORTED_MARKDOWN_EXTENSION`；
- 表格/结构：`TABLE_STRUCTURE_INVALID`、`SOURCE_LOCATOR_INVALID`、`PARSE_QUALITY_GATE_FAILED`；
- Embedding/索引：`EMBEDDING_BATCH_FAILED`、`EMBEDDING_DIMENSION_INVALID`、`EMBEDDING_VALUE_INVALID`、`LEXICAL_INDEX_INVALID`；
- Store/交付：`MULTIPLE_KNOWLEDGE_SCOPES`、`SCHEMA_FINGERPRINT_MISMATCH`、`STORE_STATISTICS_MISMATCH`、`MANIFEST_VALIDATION_FAILED`、`ARTIFACT_VERIFY_FAILED`、`BUILD_OUTPUT_ALREADY_EXISTS`（已有 Bundle 或并发发布占用目标输出，禁止覆盖）。

新增原因码必须说明 Severity、所属阶段和是否阻止 `publishable=true`，不能用一个笼统 `BUILD_FAILED` 取代可操作诊断。

### 测试文件

- `SourceLocatorTest.java`
- `StructuredBlockTest.java`
- `DocumentParserRegistryTest.java`
- `ParseDiagnosticTest.java`

### Phase 1 测试门禁

- CLI 三个子命令的参数、退出码、取消和帮助测试通过。
- Corpus/Config 正反例、Hash、Scope、未知字段测试通过。
- 路径逃逸、符号链接、URL、文件格式伪装和 Charset 冲突测试通过。
- Parser Registry 和统一 DTO 契约通过。
- `gradlew.bat test check installDist` 通过。

---

# Phase 2：多格式 Parser 与结构恢复

## 目标

让 PDF、静态 HTML、Markdown 通过统一 Registry 产生同一语义的 `StructuredBlock`、`TableBlock`、`SourceLocator` 和诊断。任何 Parser 都不得发起网络、执行脚本或进入 Chunk/Embedding。

## Task 2.1：实现 PDF 分类、逐页字形提取与阅读顺序恢复

### 创建文件

`.../parser/pdf/`：

- `PdfDocumentParser.java`
- `PdfDocumentClassifier.java`
- `PdfClassification.java`
- `PdfPageAnalysis.java`
- `PdfGlyphExtractor.java`
- `PdfLayoutAnalyzer.java`
- `PdfReadingOrderResolver.java`
- `PdfHeadingDetector.java`
- `PdfHeaderFooterDetector.java`
- `PdfPrintedPageLabelResolver.java`
- `PdfBlockAssembler.java`

### 实现顺序

1. 以 PDFBox 安全打开，禁用不必要外部行为，限制页数、对象数和内存使用。
2. 在解析正文前分类 `TEXT_BASED / MIXED / SCANNED / ENCRYPTED / INVALID`。
3. SCANNED、INVALID，以及需要密码或禁止内容提取的 ENCRYPTED 直接产生稳定硬失败；无密码且 PDFBox 访问权限允许内容提取的加密 PDF 仅以只读方式解析，不修改、不解密、不绕过权限。
4. MIXED 默认失败；只有所有不可解析页均在 Corpus 配置中 `APPROVED` 且审核字段完整时才继续。
5. 按页读取 Unicode、坐标、字体、字号和字符方向；不把一次 `getText()` 当最终结果。
6. 按栏、行、块恢复阅读顺序；多栏不得左右行交叉。
7. 根据跨页重复、位置和频率识别页眉页脚，只进入诊断，不进入正常 Chunk 正文。
8. 标题识别组合字号、字重、间距、编号和邻接关系；阈值配置化。
9. 物理页从 1 开始；印刷页标签只有可靠解析/人工映射时记录。
10. 每个 Block 保留可靠页范围、BoundingBox 和 Confidence。
11. PDF 中的 JavaScript、Launch Action、嵌入附件、外部链接和多媒体只作为被忽略/诊断的对象，禁止执行、打开或提取为额外语料；测试应证明 Parser 不产生外部进程或网络访问。

### 边界

- 不 OCR。
- 不以颜色单一特征识别 Warning。
- 不因某页提取为空就自动忽略；必须分类或人工审核。

### 测试文件

- `PdfDocumentClassifierTest.java`
- `PdfReadingOrderResolverTest.java`
- `PdfHeadingDetectorTest.java`
- `PdfHeaderFooterDetectorTest.java`
- `PdfPrintedPageLabelResolverTest.java`
- `PdfDocumentParserTest.java`

## Task 2.2：实现 PDF 文本型表格、跨页表格与 Warning

### 创建文件

`.../parser/pdf/`：

- `PdfTableExtractor.java`
- `TabulaPdfTableExtractor.java`
- `PdfTableStrategyResolver.java`
- `PdfCrossPageTableMerger.java`
- `PdfWarningDetector.java`

`.../parser/table/`：

- `TableNormalizer.java`
- `TableValidator.java`
- `TableTextRenderer.java`

### 实现要求

1. Lattice 用于有边框表格，Stream 用于基于文本间距表格；允许 Document/Page Range 配置覆盖。
2. 自动策略必须记录实际选择和 Confidence。
3. Tabula 失败不得把错乱内容静默并入段落。
4. 跨页表格只有在表头、列数、位置和连续性满足规则时合并，否则产生诊断。
5. 统一校验空表头、列数不一致、合并单元格、超大单元格和嵌套结构。
6. `TableTextRenderer` 输出自包含文本，每行重复表名/表头语义。
7. Warning 尽量把标题、条件、禁止事项、后果和解除条件组合在相邻 Block/Parent 候选中。
8. 被批准排除的页不能悄悄切断跨页表格或 Warning；发现关键上下文跨排除页时发布失败。

### 测试文件

- `PdfTableStrategyResolverTest.java`
- `TabulaPdfTableExtractorTest.java`
- `PdfCrossPageTableMergerTest.java`
- `TableNormalizerTest.java`
- `TableValidatorTest.java`
- `PdfWarningDetectorTest.java`

## Task 2.3：实现严格静态 HTML Parser

### 创建文件

`.../parser/html/`：

- `StaticHtmlDocumentParser.java`
- `HtmlSecurityPolicy.java`
- `HtmlDomSanitizer.java`
- `HtmlContentRootSelector.java`
- `HtmlNoiseFilter.java`
- `HtmlStructureWalker.java`
- `HtmlHeadingPathTracker.java`
- `HtmlSourceLocatorFactory.java`
- `HtmlTableExtractor.java`
- `HtmlDynamicContentDetector.java`

### 安全实现要求

1. 从已解码的本地 String/Reader 构造 DOM，禁止使用 Parser 的 URL/连接 API。
2. 不配置网络 Fetcher；测试应安装“任何网络请求即失败”的保护。
3. 移除 `script/style/noscript/template/iframe/canvas/svg`、表单控件和事件处理器。
4. 不加载 CSS、字体、图片、iframe、媒体或本地子资源。
5. Link 只保留可见文本；Image 只保留受限、规范化的非空 alt。
6. 优先显式内容根，其次 `<main>`、`<article>`；多个匹配或非法 Selector 产生确定性错误。
7. 导航、页眉、页脚、侧栏、Cookie 提示按配置和版本化规则去除，不能基于任意 class 名盲删正文。
8. DOM 顺序保留标题、段落、列表、定义列表、引用、代码和表格相对关系。
9. 标题建立 headingPath；只保留源文件中真实、规范化、文档内唯一的 Element ID。
10. DOM Table 直接形成 TableBlock，不经过 Tabula；rowspan/colspan 展开失败必须诊断。
11. 脚本移除后无足够正文返回 `DYNAMIC_HTML_UNSUPPORTED`，不启动动态渲染兜底。
12. 限制 DOM 节点深度、数量、文本长度和表格单元格数，防止资源耗尽。
13. `contentRootSelector` 和排除 Selector 限制数量、长度与允许语法；拒绝解析器不支持或可能造成灾难性匹配的表达式，不把非法 Selector 当作“未匹配”静默继续。

### 测试文件

- `HtmlDomSanitizerTest.java`
- `HtmlContentRootSelectorTest.java`
- `HtmlStructureWalkerTest.java`
- `HtmlSourceLocatorFactoryTest.java`
- `HtmlTableExtractorTest.java`
- `StaticHtmlSecurityTest.java`
- `StaticHtmlDocumentParserTest.java`

## Task 2.4：实现 CommonMark + GFM Table Parser

### 创建文件

`.../parser/markdown/`：

- `MarkdownDocumentParser.java`
- `MarkdownAstWalker.java`
- `MarkdownHeadingPathTracker.java`
- `MarkdownSourcePositionResolver.java`
- `MarkdownTableExtractor.java`
- `MarkdownFrontMatterDetector.java`
- `MarkdownExtensionPolicy.java`
- `MarkdownRawHtmlAdapter.java`

### 实现要求

1. Parser 配置固定 CommonMark，V1 只显式启用 GFM Table。
2. 保持 ATX/Setext 标题、段落、嵌套列表、引用、分隔线和围栏代码块顺序。
3. GFM Table 进入统一 TableNormalizer。
4. Link 只保留可见文本；Image 只保留非空、安全长度 alt，不读取目标。
5. Raw HTML 通过 `HtmlDomSanitizer` 的同一安全策略，不复制第二套清理代码。
6. Front Matter 只记录并排除，不能覆盖 Corpus Metadata。
7. Parser 支持 Source Span 时输出 1-based 行号；不可靠时使用 headingPath + sectionOrdinal。
8. 脚注、数学、Mermaid、自定义容器等未批准扩展输出可读纯文本或 `UNSUPPORTED_MARKDOWN_EXTENSION`，不得静默解释。
9. 空文档、只有 Front Matter、只有无文本链接或无 alt 图片时硬失败。
10. 限制 AST 节点、嵌套深度、文本和表格规模。

### 测试文件

- `MarkdownAstWalkerTest.java`
- `MarkdownSourcePositionResolverTest.java`
- `MarkdownTableExtractorTest.java`
- `MarkdownFrontMatterDetectorTest.java`
- `MarkdownRawHtmlAdapterTest.java`
- `MarkdownExtensionPolicyTest.java`
- `MarkdownDocumentParserTest.java`

## Task 2.5：统一解析质量评估与跨格式语义检查

### 创建文件

`.../parser/`：

- `ParseQualityEvaluator.java`
- `ParseQualitySummary.java`
- `CrossFormatStructureValidator.java`
- `LocatorContinuityValidator.java`

### 实现要求

1. 汇总每个文档的字符数、异常字符比例、Block 分类数量、表格和 Locator 完整性。
2. PDF 汇总页分类、空页、排除页、表格失败和页码标签。
3. HTML 汇总 DOM 节点、正文根、危险/噪声移除、外部引用和动态页面诊断。
4. Markdown 汇总 AST 节点、GFM Table、Raw HTML、Front Matter 和未支持扩展。
5. `CrossFormatStructureValidator` 只校验统一协议，不要求三种文档内容相同。
6. Block headingPath 必须等于 Locator headingPath；跨不连续 Locator 内容要求拆分而非伪造范围。
7. APPROVED 阈值才允许正式发布；TEST_ONLY 阈值只能生成不可发布的开发报告。

### 测试文件

- `ParseQualityEvaluatorTest.java`
- `CrossFormatStructureValidatorTest.java`
- `LocatorContinuityValidatorTest.java`

### Phase 2 测试门禁

- 总体设计第 23.1 节全部 PDF/HTML/Markdown Parser 用例有对应测试。
- 安全测试证明 HTML 不联网、不执行脚本，Markdown 不读取链接/图片。
- PDF 扫描、损坏、需要密码或禁止内容提取的加密文件、MIXED 未审核路径硬失败；允许内容提取的无密码加密 PDF 走只读解析路径。
- 三种格式均生成统一 Block/Table/Locator。
- `validate` 命令可完成全 Corpus 解析并输出非发布质量摘要。
- `gradlew.bat test check` 通过。

---

# Phase 3：Chunk、Embedding 与 Lexical Index

## 目标

把结构化文档转换为稳定 Parent/Child Chunk，为每个 Child 生成合法向量和 postings。Phase 3 不写正式 ObjectBox，但应形成完整的 Store 写入输入。

## Task 3.1：实现 Heading-aware Parent/Child Chunk

### 创建文件

`.../chunk/`：

- `DocumentChunker.java`
- `HeadingAwareParentChunker.java`
- `ChildChunkSplitter.java`
- `ChunkBoundaryPolicy.java`
- `WarningCohesionPolicy.java`
- `TableChunkSplitter.java`
- `SourceLocatorMerger.java`
- `ParentChunk.java`
- `ChildChunk.java`
- `ChunkResult.java`
- `ChunkDiagnostic.java`
- `TokenEstimator.java`

### 实现要求

1. Parent 代表完整主题；Child 是可检索的自包含证据。
2. 标题路径、文档标题、表格标题、Warning 类型传递到 Chunk。
3. 不产生只有代词、半句或脱离表头的表格行 Child。
4. Warning 的条件、禁止事项和后果优先保持在同一 Parent。
5. 长表格按配置行数拆分，每个 Child 重复表名和表头。
6. Overlap 只能在连续普通文本边界使用，禁止制造重复 ID 或跨不连续 Locator。
7. Locator 合并必须形成真实连续范围；不能连续时拆 Chunk。
8. TokenEstimator 与 Android Context/Evidence 使用同一版本化估算口径和 Golden Case。
9. 配置超过安全上下限或仍为 TEST_ONLY 时，不允许 publishable build。

### 测试文件

- `HeadingAwareParentChunkerTest.java`
- `ChildChunkSplitterTest.java`
- `WarningCohesionPolicyTest.java`
- `TableChunkSplitterTest.java`
- `SourceLocatorMergerTest.java`
- `TokenEstimatorGoldenTest.java`

## Task 3.2：实现稳定标识、Embedding 文本与可重复顺序

### 创建文件

- `.../chunk/StableIdGenerator.java`
- `.../chunk/EmbeddingTextRenderer.java`
- `.../chunk/ChunkCanonicalizer.java`
- `.../pipeline/ReproducibleBuildClock.java`

### 实现要求

1. 使用 SHA-256，不使用 Java `hashCode()`。
2. `parentChunkId` 输入包含 Document、标题路径、稳定顺序和算法版本。
3. `chunkId` 输入包含 Document、Parent、Child 序号、规范化内容 Hash 和算法版本。
4. 相同输入/配置产生相同 ID；内容、Locator、Chunk 配置变化必须改变相关 ID 或 Bundle 兼容信息。
5. Embedding 文本严格使用总体设计模板：文档、位置、类型、内容。
6. Document、Parent、Child 和 Term 在下游写入前使用稳定排序。
7. `SOURCE_DATE_EPOCH` 存在时用于可重复时间；同一输入、同一时间种子和依赖版本应产生相同逻辑清单。
8. 文档 Embedding 文本和 Android Query 预处理的版本、Unicode/空白规则及 Hash 写入 `rag-schema/test-vectors/embedding-input-v1.json`；两端必须运行同一 Golden，防止仅模型名相同但预处理漂移。

### 测试文件

- `StableIdGeneratorTest.java`
- `EmbeddingTextRendererTest.java`
- `EmbeddingInputGoldenTest.java`
- `ReproducibleOrderingTest.java`

## Task 3.3：实现 DashScope 文档 Embedding、批处理与缓存恢复

### 创建文件

`.../embedding/`：

- `DocumentEmbeddingClient.java`
- `DashScopeDocumentEmbeddingClient.java`
- `EmbeddingRequest.java`
- `EmbeddingBatchPlanner.java`
- `EmbeddingBatchResult.java`
- `EmbeddingVectorValidator.java`
- `EmbeddingRetryPolicy.java`
- `EmbeddingCache.java`
- `FileEmbeddingCache.java`
- `EmbeddingCacheKey.java`
- `EmbeddingBuildCoordinator.java`
- `EmbeddingException.java`

### 实现要求

1. API Key 只从 `DASHSCOPE_API_KEY` 读取；缺失时 `build` 在云阶段前受控失败，`validate` 不需要 Key。
2. Cache Key 至少包含 Provider、Model、Dimension、Template Version 和 Embedding Text SHA-256。
3. Cache 只保存向量和必要校验元数据，不保存 API Key；损坏/版本不匹配条目忽略并重算。
4. Batch Planner 遵守 Phase 0 实测接口限制；保留输入序号，响应必须严格映射回原 Child。
5. 重试只覆盖 429、可恢复 5xx、连接重置等瞬时错误；认证、参数、维度错误不重试。
6. 尊重 Retry-After，使用有上限指数退避和抖动；收到取消不继续重试。
7. 校验每个 Child 恰好一个 1024 维向量，且无 NaN/Infinity。
8. 任一 Child 最终失败时，正式 Build 失败；可以保留 Cache 供下次恢复，但不能发布不完整 DB。
9. 默认日志只记录 Batch ID、数量、字符/Token 估算、状态和耗时，不记录完整正文。
10. HTTP 响应 Body 和 API Key 不进入异常的用户可见文本。

### 测试文件

- `EmbeddingBatchPlannerTest.java`
- `EmbeddingVectorValidatorTest.java`
- `EmbeddingRetryPolicyTest.java`
- `FileEmbeddingCacheTest.java`
- `DashScopeDocumentEmbeddingClientTest.java`（Mock Server）。
- `EmbeddingBuildCoordinatorTest.java`：顺序、部分失败、恢复、取消。

## Task 3.4：实现跨端一致 Analyzer 与 postings

### 创建文件

`.../lexical/`：

- `LexicalAnalyzer.java`
- `CjkLatinLexicalAnalyzer.java`
- `LexicalAnalyzerConfig.java`
- `LexicalDocument.java`
- `TermFrequencyCounter.java`
- `LexicalPosting.java`
- `LexicalIndex.java`
- `LexicalIndexBuilder.java`
- `LexicalIndexValidator.java`

### 创建共享测试向量

- `rag-schema/test-vectors/lexical-analyzer-v1.json`

### 实现要求

1. Unicode 兼容规范化、空白和标点规则版本化。
2. 中文连续文本生成 bigram/trigram。
3. 英文字母小写，同时保留 `ACC`、`ESP`、`AUTO HOLD` 等可精确匹配语义。
4. 故障码、版本号、字母数字组合保留完整 Token。
5. 停用词、数字和符号策略写入 Analyzer Config/Version。
6. 仅 Child 进入 Index；Parent 不进入 postings。
7. 计算每个 Child `lexicalDocumentLength`、每个 term 的 df/tf 和全局 avgdl。
8. Term 和 postings 顺序确定；数组长度、Child 引用和 df 一致。
9. Android Analyzer 必须运行同一 Golden 文件；任一端结果不一致即兼容失败。

### 测试文件

- `CjkLatinLexicalAnalyzerTest.java`
- `LexicalAnalyzerGoldenTest.java`
- `TermFrequencyCounterTest.java`
- `LexicalIndexBuilderTest.java`
- `LexicalIndexValidatorTest.java`

### Phase 3 测试门禁

- Chunk 稳定 ID、Locator、Warning、表格自包含和预算用例通过。
- Embedding 顺序、缓存、重试、取消、维度和数值校验通过。
- 任一向量缺失时完整 Build 不可发布。
- Analyzer Golden 在 CLI 通过，并交付 Android 端执行。
- postings 的 df/tf/length/avgdl 和确定性排序通过。
- `gradlew.bat test check` 通过。

---

# Phase 4：ObjectBox 建库、Manifest、报告与原子发布

## 目标

把 Phase 3 的完整内存/中间模型写成 Android 可消费的交付 Bundle，并保证 Store Metadata、Manifest、Report 和文件 Hash 相互一致，失败不污染旧输出。

## Task 4.1：实现领域模型到共享 Entity 的严格映射

### 创建文件

`.../store/`：

- `KnowledgeStoreMetadataMapper.java`
- `KnowledgeDocumentEntityMapper.java`
- `KnowledgeChunkEntityMapper.java`
- `LexicalTermEntityMapper.java`
- `StoreWriteModel.java`
- `StoreModelValidator.java`

### 实现要求

1. Document Metadata 只从已验证 Corpus 复制，不从正文二次推断。
2. Child 冗余五项适用 Metadata；Parent embedding 为空，Child embedding 合法。
3. SourceLocator 扁平化遵守格式互斥；不适用数值写 0、字符串为空。
4. headingPath 使用版本化稳定分隔/编码，不允许简单拼接产生歧义。
5. `supportedSourceFormats` 和计数使用确定性排序 JSON。
6. Store 只能有一条 Metadata；所有 Document/Chunk 属于同一 Bundle Scope。
7. 写库前完成全量交叉引用：Document、Parent、Child、posting 和向量。

### 测试文件

- 四个 Entity Mapper Test。
- `StoreModelValidatorTest.java`。
- 三种 SourceLocator 落库/恢复契约测试。

## Task 4.2：实现确定性 ObjectBox Writer 和 Store 自检

### 创建文件

- `.../store/ObjectBoxKnowledgeStoreWriter.java`
- `.../store/ObjectBoxStoreFactory.java`
- `.../store/DeterministicEntityWriter.java`
- `.../store/ObjectBoxStoreVerifier.java`
- `.../store/StoreStatistics.java`
- `.../store/StoreWriteResult.java`

### 写入顺序

1. 在本次 Run 独享的 staging Store 目录创建 Store。
2. 按稳定 Document ID 写 Document。
3. 按 Parent ID 写 Parent，再按 Child ID 写 Child，取得实际 ObjectBox long ID 映射。
4. 使用 `chunkId → entityId` 构建 Term postings，按 Term 稳定排序写入。
5. 最后写唯一 Store Metadata 和完整统计。
6. 提交并关闭 Store，确保文件落盘。
7. 重新打开 Store，验证计数、唯一性、Scope、Metadata、向量属性和 postings。
8. 执行至少一次 Dense Query、Term Query 和精确 Metadata 查询。
9. 自检完成后关闭 Store，再进行文件 Hash。

### 实现要求

- 写库过程不复用旧 Store，不做增量更新；V1 永远全量重建。
- ObjectBox 内部 long ID 不暴露到 Manifest/Evidence 协议。
- postings 只引用当前同一 Store 的 Child Entity ID。
- 发生异常立即关闭 Store，保留失败报告，不发布 staging。
- 不通过手工文件拼接或复制旧 HNSW 规避全量重建。

### 测试文件

- `ObjectBoxKnowledgeStoreWriterTest.java`
- `DeterministicEntityWriterTest.java`
- `ObjectBoxStoreVerifierTest.java`
- `ObjectBoxCorruptStoreTest.java`

## Task 4.3：生成稳定 Manifest 与完整 Build Report

### 创建文件

`.../artifact/`：

- `KnowledgeBundleManifest.java`
- `ManifestBuilder.java`
- `ManifestValidator.java`
- `ManifestWriter.java`
- `BundleFileHasher.java`

`.../report/`：

- `BuildReport.java`
- `DocumentBuildReport.java`
- `ParserBuildSummary.java`
- `ChunkBuildSummary.java`
- `EmbeddingBuildSummary.java`
- `StoreBuildSummary.java`
- `BuildReportCollector.java`
- `PublishabilityEvaluator.java`
- `BuildReportWriter.java`

### Manifest 要求

1. 覆盖总体设计第 11.2 节全部字段。
2. `data.mdb` 关闭后再计算 size/SHA-256。
3. ObjectBox、Schema、HNSW、Embedding、Analyzer、Parser、Chunk、Locator 的版本/Hash 均来自真实锁定配置。
4. Corpus 计数与 Store 实际统计一致。
5. 禁止保留 `<pinned-version>` 等示例占位符。
6. JSON Key/Array 顺序稳定；Manifest 自身不包含本机绝对路径、API Key 或异常堆栈。
7. 生成结果必须通过共享 `rag-schema/contracts/knowledge-bundle-manifest.schema.json`；Android Parser 的正反例测试使用同一 Schema/Golden，禁止两端各维护一套必填字段。
8. Schema、HNSW、Parser、Chunk 等 Fingerprint 使用 `compatibility-fingerprints-v1.json` 规定的规范化算法和输入集合，不能只比较双方恰好生成的字符串。

### Build Report 要求

1. 逐格式文件数、成功/失败和 Parser 版本。
2. 逐文档 Hash、Charset、字符/异常比例、Block/Table/Warning/Locator 统计。
3. PDF 页、扫描/空白、排除页、表格和页码诊断。
4. HTML DOM、安全移除、外部引用、正文根和动态内容诊断。
5. Markdown AST、Table、Raw HTML、Front Matter 和未支持扩展诊断。
6. Chunk、Embedding、Lexical、ObjectBox 统计与耗时。
7. 所有 ERROR/WARNING、原因码和审核状态。
8. `publishable=true` 只能由 `PublishabilityEvaluator` 在全部硬门禁通过后产生，CLI 参数不能强制覆盖。
9. 报告可以含受控相对 Source 路径和摘要，但不包含完整官方文档正文。

### 测试文件

- `ManifestBuilderTest.java`
- `ManifestValidatorTest.java`
- `BuildReportCollectorTest.java`
- `PublishabilityEvaluatorTest.java`
- `DeterministicArtifactJsonTest.java`

## Task 4.4：实现完整 Pipeline、工作目录和原子输出

### 创建文件

`.../pipeline/`：

- `ValidatePipeline.java`
- `BuildPipeline.java`
- `VerifyPipeline.java`
- `BuildPhase.java`
- `BuildProgressListener.java`
- `BuildWorkspace.java`
- `BuildCheckpoint.java`
- `BuildCheckpointStore.java`

`.../artifact/`：

- `ArtifactPublisher.java`
- `BundleLayout.java`
- `ArtifactPublishResult.java`

### Build Pipeline 顺序

```text
Config/Corpus
→ Input Security
→ Parser
→ Parse Quality
→ Parent/Child Chunk
→ Document Embedding
→ Lexical Index
→ Store Model Validation
→ ObjectBox staging write
→ Store reopen/self-check
→ data.mdb hash
→ Manifest
→ Build Report
→ final verify
→ atomic publish
```

### 原子发布要求

1. Parse/Chunk/Embedding Checkpoint 使用 `work/runs/<runId>`；最终 ObjectBox 和三个交付候选写入 `output` 父目录下的唯一隐藏 staging 目录，确保与目标路径位于同一 FileStore，不直接写目标 output。
2. 正式输出固定只有 `data.mdb`、`manifest.json`、`build-report.json`。
3. 最终 Verify 和 `publishable=true` 前不替换已有 output。
4. 最终发布前验证 staging 与 output parent 属于同一 FileStore，再执行目录原子 rename；若目标文件系统不支持原子目录移动，Phase 0/实现阶段必须锁定“版本目录 + 完成标记”的可恢复替代策略并测试，不能运行时悄悄退化为逐文件覆盖。
5. V1 不提供覆盖开关：目标 output 路径必须不存在，只有其父目录可以预先存在；已有发布目录要求调用者选择新的 Bundle Version/输出路径，CLI 不删除或替换旧目录。这样 staging 目录才能通过同文件系统 rename 成为完整 output。
6. 发布移动失败时保留旧输出；临时目录带 runId，下一次运行可识别并清理。
7. Checkpoint 只复用经过 Hash/版本验证的 Parse/Embedding 中间结果；Schema/Parser/Chunk/Template 变化使相应阶段失效。
8. 中断后不发布部分 Store；Embedding Cache 可以保留。

### Verify Pipeline

- 不信任 Manifest 声明，重新计算 DB Hash/大小。
- 打开 Store 比对 Metadata、计数、Scope、Schema、HNSW、版本和格式统计。
- 执行最小 Dense/Lexical/Metadata 查询。
- Dense 自检使用 Store 内已知 Child 的合法向量作为查询向量，不调用 DashScope，因此 `verify` 不需要 API Key 或网络。
- 检查每个 Document/Parent/Child/Posting 的引用一致性。
- 检查所有 SourceLocator 格式语义。
- 输出受控 Verify 摘要，不改动 Bundle。

### 测试文件

- `BuildPipelineTest.java`
- `ValidatePipelineTest.java`
- `VerifyPipelineTest.java`
- `BuildCheckpointStoreTest.java`
- `ArtifactPublisherTest.java`：旧输出保留、staging 失败、中断和重复运行。

### Phase 4 测试门禁

- Fixture 完整 Build 生成三个规定文件。
- Manifest、Store Metadata、Report 的版本、Hash、Scope和计数完全一致。
- Verify 能发现 DB 篡改、Manifest 篡改、坏 Schema、坏 postings 和缺失向量。
- 失败/取消不会覆盖旧 output。
- 使用固定 `SOURCE_DATE_EPOCH` 的逻辑清单、Manifest 和 Report 可重复。
- `gradlew.bat test check installDist distZip` 通过。

---

# Phase 5：测试语料、真实评测、Android 联合门禁与发布

## 目标

用可审计 Fixture 覆盖安全和结构边界，再用真实受审车辆资料确定 Parser/Chunk/HNSW/质量参数，最终生成 Android 可用的正式候选 Bundle。

## Task 5.1：建立可审计测试 Fixture 与 Golden 结果

### 创建目录

```text
tools/rag-indexer/src/test/resources/fixtures/
├── corpus/
├── pdf/
├── static_html/
├── markdown/
├── security/
├── cross_format/
└── golden/
```

### Fixture 要求

- 每个 Fixture 有 `README.md` 说明来源、生成方法、许可和预期结果。
- PDF 尽可能由 Java 测试 Fixture Builder 生成，减少来源不明二进制；复杂布局样本需有明确授权。
- HTML/Markdown 使用本地合成文本，不含真实外部请求依赖。
- 安全样本包含脚本、iframe、远程图片、file 链接、路径逃逸、深层 DOM/AST 和超大表格。
- Cross-format 使用相同合成知识分别表达为 PDF/HTML/Markdown，以检查归一化和召回一致性。
- Golden JSON 包含 Blocks、Tables、Locators、Chunks、Analyzer Tokens、IDs 和 Report 摘要。
- Golden 更新必须有原因，禁止测试失败后无审查地整体重录。

### 创建测试工具

- `.../test/fixture/PdfFixtureFactory.java`
- `.../test/fixture/CorpusFixtureFactory.java`
- `.../test/golden/GoldenFileAssertions.java`

## Task 5.2：补齐全链路、安全与故障注入测试

### 创建文件建议

- `.../e2e/ValidateCommandEndToEndTest.java`
- `.../e2e/BuildCommandEndToEndTest.java`
- `.../e2e/VerifyCommandEndToEndTest.java`
- `.../e2e/CrossFormatBuildTest.java`
- `.../e2e/ReproducibleBuildTest.java`
- `.../security/OfflineOnlySecurityTest.java`
- `.../security/CorpusPathEscapeTest.java`
- `.../failure/EmbeddingFailureRecoveryTest.java`
- `.../failure/ArtifactAtomicityTest.java`

### 用例矩阵

- 中文、中英混合、多栏、标题、列表、Warning PDF。
- Lattice、Stream、跨页表格。
- 空页、MIXED、SCANNED、ENCRYPTED、INVALID PDF。
- APPROVED 排除页允许，缺审核字段/关键内容跨页拒绝。
- UTF-8、显式非 UTF-8、Charset 冲突 HTML。
- `<main>/<article>`、配置根、噪声、危险节点、DOM Table。
- HTML 绝不联网/执行脚本，动态空正文受控失败。
- CommonMark、GFM Table、Raw HTML、Front Matter、未支持扩展。
- Markdown 链接/图片只保留文本，不读取目标。
- 路径/符号链接逃逸、格式声明不符、超限。
- Chunk/ID/Locator 稳定性，Warning 和表格自包含。
- Embedding 批序、Cache、429/5xx、认证失败、维度、NaN/Infinity、取消。
- Analyzer/postings、单 Scope、Metadata、ObjectBox 统计。
- Manifest Hash、配置 Hash、HNSW Fingerprint。
- 旧 output 回滚、进程中断、staging 残留和 Verify 篡改检测。

## Task 5.3：使用真实文档校准参数与质量门禁

### 前置条件

- 官方资料已完成使用授权和 Metadata 人工审核。
- PDF、静态 HTML、Markdown 实际数量可以继续变化；数量不超过先前预计不影响工程骨架。
- 每份文档具有 SourceFormat、版本、Scope、SHA-256、Charset/页审核信息。

### 创建文件

- `docs/testresult/rag/offline_parser_quality_report.md`
- `docs/testresult/rag/offline_chunk_retrieval_evaluation.md`
- `tools/rag-indexer/schemas/retrieval-evaluation.schema.json`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RetrievalEvaluationDataset.java`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RetrievalEvaluationLoader.java`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/OfflineDenseEvaluator.java`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RetrievalMetrics.java`
- `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RetrievalEvaluationReportWriter.java`
- 正式配置文件的保存位置由资料保密策略决定；不得默认提交真实文档或凭证。

### 工作步骤

1. 对每份真实文档运行 `validate`，人工抽查阅读顺序、标题、Warning、表格和 Locator。
2. 确定 PDF 分类和扫描页阈值。
3. 确定 HTML 内容根/噪声规则、Markdown 扩展诊断是否足够。
4. 确定单文件/总语料/DOM/AST/Table/异常字符质量阈值。
5. 对 Parent/Child 长度、Overlap 和表格行数做检索对比。
6. 对 HNSW 参数做体积、构建时间、Android 查询延迟和 Recall 对比。
7. 使用版本化评测集和 `evaluate` 命令计算 Dense Recall@K、MRR；Query Embedding 使用与 Android 相同模型/预处理 Golden，评测命令只读 Store、不成为生产构建必经阶段。
8. 将最终参数状态从 TEST_ONLY 提升为 APPROVED，记录数据集版本、理由和负责人。
9. 任何关键知识缺失、表格错列、Warning 分离或 Locator 不可靠均阻止发布。
10. Android 端继续负责 BM25/RRF/Rerank、No-Evidence、Citation 和最终回答 Faithfulness；离线 Dense 指标不得替代 Android 完整 Hybrid 评测。

### 测试文件

- `RetrievalEvaluationLoaderTest.java`：未知 ID、重复 Query、空期望集合和 Schema 错误。
- `OfflineDenseEvaluatorTest.java`：Recall@K/MRR、稳定 Tie-breaker、Scope 过滤和只读 Store。
- `RetrievalEvaluationReportWriterTest.java`：不写 Query 全文到公开报告，数据集版本和 Bundle Hash 完整。

### 边界

- 不把真实文档提交进 Git，除非授权和仓库策略明确允许。
- 不为了提高成功率把扫描页、动态 HTML 或未支持扩展静默当普通文本。
- 不通过人工编辑 ObjectBox DB 修补 Parser 问题；应修 Parser/配置后全量重建。

## Task 5.4：执行 Android 联合兼容与正式 Bundle 交付

### CLI 输出

```text
output/
├── data.mdb
├── manifest.json
└── build-report.json
```

### 联合步骤

1. CLI `verify` 对候选 Bundle 完整通过。
2. 确认 `build-report.publishable=true`，无未批准 ERROR/Warning。
3. Android 端复制同次构建的 `data.mdb` 和 `manifest.json` 到 Asset 候选位置；Report 留在审计目录。
4. Android 使用共享 `MyObjectBox` 打开，并校验 Manifest、Store Metadata、Scope、HNSW、Analyzer、格式统计。
5. 执行 Dense Query、Lexical Query、Metadata Filter。
6. PDF、HTML、Markdown 各验证一个 Chunk、Document 和 SourceLocator。
7. 验证坏 Schema、坏 Scope、坏 HNSW 配置不能激活。
8. 目标 ABI 验证首次安装、升级、回滚、检索和 APK 体积。
9. 将构建命令、CLI 版本、Git Commit、配置 Hash、Corpus Hash、Bundle Version 和产物 Hash写入交付记录。

### 创建报告

- `docs/testresult/rag/cross_runtime_bundle_compatibility_report.md`
- `docs/testresult/rag/rag_bundle_release_record.md`

## Task 5.5：完善工具使用与维护文档

### 修改文件

- `tools/rag-indexer/README.md`
- 项目根 `README.md`：按现有章节脉络增加离线工具边界、命令和交付流程。
- 必要时更新 `docs/design/` 中实现后的离线架构说明。

### README 必须说明

- Java 17、独立 Gradle Build 和运行命令。
- `validate/build/verify/evaluate` 用法及退出码；明确 evaluate 只评估 Dense，不能替代 Android Hybrid/回答评测。
- Corpus/Config Schema 和安全边界。
- API Key 环境变量，不展示真实凭证。
- PDF/静态 HTML/Markdown 支持范围与不支持项。
- Work/Cache/Output 目录和清理方式。
- 如何生成 Android Asset 候选，不直接覆盖已发布 Asset。
- Schema/Parser/Chunk/Embedding/Analyzer 变化为何必须全量重建。
- 如何执行测试、Golden 更新和跨端门禁。

### Phase 5 最终门禁

- 总体设计第 23.1 节全部离线测试覆盖并通过。
- `gradlew.bat clean test check installDist distZip` 通过。
- 真实文档 Parser/Chunk/Embedding/Store/Report 质量审核通过。
- 所有正式阈值和版本状态为 APPROVED。
- CLI Verify 和 Android 跨端门禁通过。
- 三种格式的 Evidence 能回溯真实 SourceLocator。
- 产物不含 API Key、绝对路径、完整异常堆栈或不应交付的源文档。
- `build-report.json` 未进入 APK。
- README、设计、报告和真实命令一致。

---

## 8. 文件级职责总表

| 包/目录 | 负责内容 | 明确禁止 |
|---|---|---|
| `cli` | 参数、子命令、退出码 | 直接解析文档或写数据库 |
| `config` | 构建配置、版本和 Hash | 保存凭证、接受任意 CLI 覆盖 |
| `corpus` | 人工 Metadata、安全路径、格式/Charset | 自动扫描未声明文件、推断车型 |
| `model` | 统一 Block/Table/Locator/Diagnostic | 依赖 PDFBox/Tabula/ObjectBox |
| `parser` | 源格式到统一结构 | Chunk、Embedding、网络访问 |
| `chunk` | Parent/Child、ID、Locator、Embedding Text | 调用云或写 Store |
| `embedding` | 文档向量、批处理、重试、Cache | Query Embedding、Rerank、泄漏正文日志 |
| `lexical` | Analyzer、tf/df/postings/avgdl | Android 查询和 BM25 运行时排名 |
| `store` | Entity 映射、ObjectBox 写入和自检 | 增量修补旧库、暴露内部 long ID |
| `artifact` | Manifest、Hash、原子发布 | 判断解析语义质量 |
| `report` | 诊断汇总、Publishability | 覆盖硬失败或修改业务数据 |
| `pipeline` | 阶段编排、Checkpoint、取消 | 复制各层具体算法 |
| `rag-schema` | Entity 与 Meta Model 唯一规范源 | 两端人工复制、删除 UID 重建 |

---

## 9. 关键实现不变量

以下每项必须转化为测试断言：

1. 所有输入文档均在 `corpus.json` 人工声明，并位于 Corpus Root 内。
2. SourceFormat 声明、扩展名和内容特征一致。
3. HTML/Markdown 不触发任何网络、脚本或子资源读取。
4. PDF 扫描、损坏、需要密码或禁止内容提取的加密文件和未审核 MIXED 页面不能发布；允许内容提取的无密码加密 PDF 不因加密标志本身拒绝。
5. 三种 Parser 只输出统一 Block/Table/Locator，不执行 Chunk/Embedding。
6. Block 与 Locator 的 headingPath 来自同一结构栈。
7. 表格 Child 始终自包含表名/表头；Warning 不被无条件拆散。
8. ID 使用 SHA-256，同输入和配置稳定。
9. 每个 Child 恰好有一个合法 1024 维 Embedding；任一失败则完整 Build 不可发布。
10. Analyzer 版本和 Golden 结果与 Android 一致。
11. Parent 不进入 postings；Term 数组长度和 Child Entity ID 一致。
12. 一个 Store 只有一个 `knowledgeScopeId` 和一条 Store Metadata。
13. 所有具体 Document Metadata 等于 Bundle Scope 或经审核为 `*`。
14. Entity/Property UID 只由共享 Meta Model 管理。
15. Manifest、Store Metadata、Build Report 的版本、Hash、Scope 和计数一致。
16. Hash 在 Store 关闭后计算；发布后不再修改 `data.mdb`。
17. `publishable=true` 不能由 CLI Flag 强制获得。
18. 失败、中断或取消不覆盖旧 Output。
19. API Key 不进入日志、Cache、配置、Report、Manifest 或 Store。
20. 正式交付只有 DB/Manifest 进入 APK，Report 保留在审计侧。
21. Parser/Chunk/Embedding/HNSW/Analyzer/Schema 任一协议变化均全量重建。
22. CLI 永远不进入 Android Agent 请求链。

---

## 10. Definition of Done

离线构建端只有同时满足以下条件才算完成：

- CLI 是仓库内独立 Java 17 Gradle Build，Android 根工程未包含它。
- Phase 0 许可证、依赖共存、共享 Schema 和 ObjectBox 跨端兼容通过。
- `validate/build/verify/evaluate` 命令、退出码、取消和帮助文档完整。
- Corpus/Config Schema 能表达全部 Metadata、格式选项、审核排除页和质量门禁。
- 输入路径、格式、Charset、大小和符号链接安全检查完整。
- PDF、静态 HTML、Markdown 均能生成统一、可追踪的结构模型。
- 动态 HTML、脚本、外部资源、扫描件和未支持 Markdown 不会被静默接受。
- Chunk、Embedding、Analyzer、postings 和 ObjectBox Store 全量一致。
- CLI 生成 `data.mdb`、`manifest.json`、`build-report.json`，且 Verify 通过。
- 真实阈值由受审文档与评测确定，不使用 TEST_ONLY 配置发布。
- Android 使用同一 Schema 成功打开候选 Store并通过 Dense/Lexical/Scope/Locator 门禁。
- 目标 ABI 和 APK Asset 流程通过。
- 全量测试、分发构建、质量报告、跨端报告和 README 完成。

如果真实文档、DashScope 凭证、目标 ABI 或 Android 端实现尚未到位，CLI 可以完成骨架、Parser、算法和 Fixture 测试，但最终状态只能标记为“离线构建实现完成，联合发布门禁未完成”，不能宣称整个 RAG 系统已经交付。
