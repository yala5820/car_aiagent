# 车机 Agent RAG 总体设计与跨端协议

> 文档性质：总体设计规范（Normative Design）
> 适用范围：离线 JVM CLI 知识库构建端、Android AIAgent RAG 运行端
> 下游文档：`vehicle_agent_rag_indexer_plan.md`、`vehicle_agent_android_rag_plan.md`
> 当前阶段：多格式输入扩展设计已确认，尚未进入实现
> 最后更新：2026-07-21

---

## 1. 文档目的与约束级别

### 1.1 文档目的

本文件定义车辆知识 RAG 系统的总体方案，以及离线构建端和 Android Agent 端必须共同遵守的接口、数据、版本、行为和安全协议。

后续两份实施计划应分别说明“如何实现”本文件规定的职责，但不得自行修改本文件中的跨端契约：

- 离线端计划负责 PDF、静态 HTML、Markdown 解析与结构恢复，以及统一 Chunk、Embedding、Lexical 索引和预构建 ObjectBox 数据库；
- Android 端计划负责知识库安装、混合检索、Agent Tool 接入、强制查证、Deadline、Trace 和降级；
- 两端共同对数据库 Schema、Manifest、Embedding、Lexical Analyzer、Metadata 和兼容性负责。
- ObjectBox 及各格式 Parser 的依赖审查、解析安全验证与跨端数据库兼容性必须先通过第 21.4 节规定的前置门禁，之后才能进入正式功能实现。

### 1.2 规范用语

本文使用以下约束用语：

- **必须**：不可省略，否则实现不符合总体设计；
- **禁止**：不得采用的实现或行为；
- **应当**：默认必须遵循，仅在有充分证据并同步修改总体设计时才可调整；
- **可以**：不影响跨端兼容性的可选实现；
- **V1**：本轮 RAG 建设的第一版正式实现。

### 1.3 冲突处理

若后续计划、代码实现或局部说明与本文件冲突，应按以下顺序处理：

1. 先确认是否属于已批准的总体设计变更；
2. 若属于设计变更，先更新本文件，再更新两份实施计划；
3. 若不属于设计变更，以本文件为准修正计划或实现；
4. 不允许通过局部代码绕过跨端协议。

---

## 2. 建设背景与系统定位

### 2.1 现有 Agent 基础

AIAgent 是运行在 Android 车机系统上的后台 Agent Service，通过 AIDL 向 Launcher 等调用方提供 TEXT、IMAGE、VOICE 和 CONTROL 能力。

当前 TEXT 主链已经具备：

- `AIAgentService` 请求准入、唯一终态、取消和绝对 Deadline；
- `AgentRuntime` 请求快照、Intent 路由和 ToolGroup 选择；
- `ContextOrchestrator` 统一模型输入装配和预算控制；
- `TextAgentLoopOrchestrator` 主模型、Tool Calling、安全审核和结果回填；
- `ToolRegistry + ToolDispatcher` 集中式工具注册与执行；
- Session、长期记忆、压缩和提取；
- OpenTelemetry Trace；
- Demo 阶段的 `VehicleStateMachine`。

RAG 必须接入以上主链，不建立新的 Agent 中枢。

### 2.2 RAG 定位

RAG 的唯一核心职责是：

> 根据受信车辆身份和用户查询，从官方车辆资料中检索结构化、可追溯、可引用的证据。

RAG 不是新的 Agent，不生成最终用户回答，不执行车辆动作，也不参与车辆 Safety 裁决。

职责边界如下：

| 组件 | 主要职责 |
|---|---|
| RAG | 查找官方知识证据并返回结构化 `RagResult` |
| 主 Agent | 判断是否需要知识、组织 Query、基于 Evidence 综合回答 |
| Context | 组织主模型输入及 ToolResult |
| ToolGroup | 控制本轮模型可见能力 |
| Tool 执行授权 | 校验模型请求的工具是否属于本轮允许集合 |
| Safety | 审核车辆动作是否允许执行 |
| VehicleProfile | 提供车型、年款、地区、软件版本等可信事实 |
| VehicleState | 提供车门、空调、车窗等动态运行状态 |

### 2.3 目标知识范围

V1 主要覆盖：

- 车辆功能说明和使用方法；
- 功能限制、前置条件和注意事项；
- 故障提示、故障码和常见原因；
- 车型、年款、地区、软件版本相关的官方知识；
- 为后续“车辆实时状态 + 官方知识”联合分析提供检索基础。

### 2.4 非目标

V1 不包含：

- Knowledge Subagent；
- 第二套 AgentLoop；
- GraphRAG 或 Multi-Agent RAG；
- Web RAG；
- 本地 Embedding 模型；
- 独立 Qdrant、Milvus 等数据库进程；
- 知识 OTA；
- 多模态 RAG；
- OCR 和扫描版 PDF 的正式支持；
- JavaScript 执行、浏览器渲染或动态 HTML 构建端；
- Web 抓取、远程 URL 输入、链接递归跟踪和外部资源下载；
- HTML/Markdown 图片正文、图片表格和流程图的视觉语义解析；
- 复杂维修诊断 Agent；
- RAG 参与车辆 Safety 决策；
- “知识查询 + 车辆控制”的复合执行闭环；
- 为引入 RAG 全面重构现有 IntentRouter。

---

## 3. 已确认的总体设计决策

下列决策已经确认，后续计划不得重新选择相反方案：

1. RAG 是主 Agent 的 Tool 能力，不创建 Subagent；
2. Android 端保持单 `app` 模块，在现有工程内新增独立 `rag` 包；
3. 文档构建由独立 Java 17 JVM CLI 完成，不进入 Android Agent 请求链；
4. 第一批 PDF 资料预计不超过 6 份，同时正式支持本地静态 HTML 和 Markdown 文件；
5. 普通文本和文本型表格属于 V1 正式范围；
6. PDFBox 负责 PDF 基础解析，Tabula Java 负责文本型表格结构提取；
7. 扫描件、图片表格和 OCR 暂不纳入 V1；
8. Embedding 使用 DashScope `text-embedding-v4`，维度固定为 1024；
9. 向量索引使用 ObjectBox HNSW，距离类型使用 Cosine；
10. 检索使用 Dense + Lexical/BM25 + RRF + `qwen3-rerank`；
11. 离线 CLI 直接生成预构建 ObjectBox `data.mdb`；
12. 数据库随 APK Assets 内置，Android 首次使用前只复制、校验和激活，不重新建库；
13. Android 运行时知识库按只读业务语义使用；
14. 车辆 Metadata 由 `VehicleProfileProvider` 注入，模型不能提供或覆盖；
15. Demo 阶段 `VehicleProfileProvider` 从完善后的 `VehicleStateMachine` 获取车辆身份；
16. 明确知识请求采用 REQUIRED 强制查证；
17. 没有可靠 Evidence 时禁止模型依靠参数知识补答；
18. 单请求最多执行 2 次不同的 RAG Query；
19. 知识请求使用独立的 60 秒端到端 Deadline；
20. Embedding 失败时允许降级为 Lexical-only；
21. Rerank 失败时允许使用 RRF 结果降级；
22. V1 知识更新只跟随 APK 更新，不实现在线 OTA；
23. Android RAG 核心和 JVM CLI 以 Java 17 为主，Service 装配保持现有 Kotlin 风格；
24. Release Trace 不记录完整用户 Query、完整 Evidence 和车辆唯一标识。
25. V1 一份知识库 Bundle 只对应一个 `knowledgeScopeId`，不在同一个 Store 中混放多个车型知识 Scope；
26. `KnowledgeRequirement.OPTIONAL` 仅保留为后续扩展位，V1 生产流程只产生 `NONE` 或 `REQUIRED`；
27. REQUIRED 请求只向模型开放 RAG Tool，每个 Agent 迭代最多调用 1 次、单请求最多调用 2 次，第二次必须发生在模型读取第一次结果后的下一迭代；
28. `MIXED` PDF 默认构建失败，只有 `corpus.json` 明确列出并完成人工审核的排除页才允许发布；
29. 内部检索结果与模型可见 ToolResult 分离，Dense、BM25、RRF、Rerank 等诊断信息不进入模型上下文；
30. 知识请求使用独立的 `KnowledgeMemoryPolicy`，官方知识不得被提取为用户长期记忆；最终引用由确定性的 `CitationGuard` 校验和渲染；
31. ObjectBox、PDFBox、Tabula 及 HTML/Markdown Parser 的许可证、版本和最小兼容性验证是实施前置门禁，未通过时不得默认引入依赖；
32. HTML 输入仅限本地静态 `.html` / `.htm` 文件，禁止执行 JavaScript、启动浏览器内核、访问网络、抓取链接或加载外部资源；
33. Markdown 输入仅限本地 `.md` 文件，语法基线为 CommonMark，并正式支持 GFM Table；
34. HTML 与 Markdown 的文本型表格属于 V1 正式范围，图片只允许保留安全的替代文本，不解析图片内容；
35. 三种输入格式必须通过 `DocumentParserRegistry` 归一化为同一个 `StructuredBlock` 协议，Chunk 及其后的构建链不得按源格式复制实现；
36. PDF 页码协议升级为通用 `SourceLocator`：PDF 使用页码，HTML 使用标题路径与元素锚点，Markdown 使用标题路径与可选源码行号。

---

## 4. 总体架构

### 4.1 离线构建链

```text
官方 PDF / 静态 HTML / Markdown + corpus.json
        ↓
JVM RAG Indexer CLI
        ↓
SourceFormat 检测、声明一致性与安全校验
        ↓
DocumentParserRegistry
        ├── PdfDocumentParser（PDFBox + Tabula）
        ├── StaticHtmlDocumentParser（DOM，不执行脚本）
        └── MarkdownDocumentParser（CommonMark + GFM Table）
        ↓
统一 StructuredBlock / TableBlock / SourceLocator
        ↓
Heading-aware Parent / Child Chunk
        ↓
DashScope text-embedding-v4
        +
Lexical Analyzer / BM25 Index
        ↓
ObjectBox HNSW 预构建
        ↓
data.mdb + manifest.json + build-report.json
        ↓
复制到 app/src/main/assets/rag/knowledge_db/
```

离线 CLI 可以访问 DashScope，但禁止读取 Android `local.properties`。CLI 的 API Key 必须来自进程环境变量或显式的安全运行环境，不能写入构建产物。

### 4.2 Android 运行链

```text
AIAgentService 启动
        ↓
KnowledgeStoreInstaller
        ├── 检查 Asset Manifest
        ├── 对比已安装版本
        ├── 复制到临时目录
        ├── 校验 Hash / Schema / Embedding / Analyzer
        ├── 试打开数据库并核对统计
        └── 原子切换 Active Store
        ↓
VehicleKnowledgeService READY
        ↓
可信 VehicleProfile → KnowledgeScopeResolver
        ↓
Scope 与 Active Bundle 兼容性校验
        ↓
主 Agent 判断 KnowledgeRequirement（V1: NONE / REQUIRED）
        ↓
VehicleKnowledgeTool.searchVehicleKnowledge(query)
        ↓
RequestDeadline + KnowledgeRequestState + Tool Allowlist
        ↓
Lexical Retrieval + Query Embedding + Dense Retrieval
        ↓
Metadata Eligibility Filter
        ↓
RRF → qwen3-rerank → Parent Context 补全
        ↓
内部 RagResult / RetrievalEvidence[]
        ↓
模型侧 VehicleKnowledgeToolResult / VehicleKnowledgeEvidence[]
        ↓
ToolResult 回填主 AgentLoop
        ↓
主模型基于 Evidence ID 生成回答
        ↓
CitationGuard 校验 Evidence ID 并确定性渲染来源
```

### 4.3 核心依赖方向

```text
TextAgentLoopOrchestrator
        ↓
VehicleKnowledgeTool
        ↓
VehicleKnowledgeService
        ↓
Retrieval / Ranking / Policy
        ↓
KnowledgeStoreGateway
        ↓
ObjectBox
```

禁止出现以下反向依赖：

- `rag` 核心依赖 `AIAgentService`；
- 检索器直接访问 Binder 或 Launcher；
- ObjectBox Entity 调用 AgentLoop；
- JVM CLI 依赖 Android Runtime；
- Safety 依赖 RAG 检索结果做动作授权；
- RAG 直接写入 ChatMemory 或 LongTermMemory。

知识请求的会话持久化和长期记忆抑制由 AgentLoop 侧 `KnowledgeMemoryPolicy` 负责，RAG 检索核心不得直接依赖 Memory 模块。

---

## 5. 工程边界与建议目录

### 5.1 Android 端

```text
app/src/main/java/com/hirain/aiagent/
├── rag/
│   ├── VehicleKnowledgeService.java
│   ├── model/
│   ├── document/
│   ├── store/
│   ├── retrieval/
│   ├── ranking/
│   ├── policy/
│   ├── profile/
│   └── trace/
└── tools/knowledge/
    └── VehicleKnowledgeTool.java

app/src/main/assets/rag/knowledge_db/
├── data.mdb
└── manifest.json
```

`VehicleKnowledgeTool` 只是 Agent 适配器，禁止承载数据库、Embedding、融合和 Rerank 的具体实现。

### 5.2 JVM CLI

```text
tools/rag-indexer/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── src/
    ├── main/java/
    │   └── .../parser/
    │       ├── pdf/
    │       ├── html/
    │       └── markdown/
    └── test/
```

CLI 使用独立 Gradle 构建，不加入 Android 根工程的模块列表。PDFBox、Tabula、HTML/Markdown Parser、桌面 ObjectBox 等依赖只存在于 CLI 构建中，不进入 Android APK 运行依赖。

共享 Schema 建议作为源码目录放在仓库根目录，但不加入 Android 模块列表：

```text
rag-schema/
├── src/main/java/com/hirain/aiagent/rag/store/entity/
└── objectbox-models/default.json
```

最终目录名可以在 Phase 0 验证后微调，但“唯一规范源、两端禁止人工复制演进”的约束不得改变。

### 5.3 ObjectBox Schema 共享

预构建数据库要求 CLI 与 Android 使用完全一致的 ObjectBox Meta Model。两端必须共同遵守：

- Entity 名称、属性名称、属性类型、索引定义和 UID 必须一致；
- `objectbox-models/*.json` 必须作为版本控制资产提交；
- 禁止删除 Meta Model 文件后重新生成 UID；
- 禁止在 CLI 和 Android 分别维护两份可以独立演进的 Schema；
- 仓库必须提供唯一的源码级 Schema 根目录，例如 `rag-schema/`，集中保存 Entity 源码和规范 Meta Model；该目录是共享源码而不是新的 Android RAG Library 模块；
- Android `app` 与独立 JVM CLI 必须通过 `sourceSets` 直接消费同一份 Entity 源，或通过确定性 Schema 同步任务把规范源物化到 ObjectBox 插件要求的位置；
- 若采用物化方式，模块内副本属于生成输入，禁止人工修改，构建前后必须校验其 SHA-256 与规范源一致；
- ObjectBox 插件能否扫描共享 `sourceSets`、能否消费同步后的 Meta Model，必须在前置兼容性验证中实测，不得仅根据设计推断；
- 构建产物必须携带 `schemaFingerprint`，Android 打开前必须比对；
- 任一 Entity、Property 或 HNSW 配置变化都必须提升 Schema/Bundle 版本并重新构建数据库。

ObjectBox 使用 Meta Model ID/UID 保证数据库与生成代码的一致性，因此 Meta Model 文件属于协议的一部分，而不是可删除的生成缓存。

---

## 6. 共同领域模型

### 6.1 标识符规则

所有跨端业务标识均使用稳定字符串，不向 Agent 暴露 ObjectBox 内部 `long id`。

| 标识符 | 生成规则 | 稳定性要求 |
|---|---|---|
| `bundleId` | 知识库产品标识 | 跨版本稳定 |
| `bundleVersion` | 每次正式构建唯一 | 内容变化必须变化 |
| `knowledgeScopeId` | 车型、配置、地区与知识版本映射出的知识范围标识 | 同一兼容知识范围稳定 |
| `documentId` | 车型/地区/类型/文档版本组合或配置指定 | 同一文档版本稳定 |
| `parentChunkId` | Document + 标题路径 + 顺序的确定性 Hash | 相同输入和算法版本稳定 |
| `chunkId` | Document + Parent + Child 顺序 + 内容 Hash | 相同输入和算法版本稳定 |
| `retrievalEvidenceId` | Bundle + Chunk + 查询阶段生成 | 内部检索、Trace 与评测可追踪 |
| `evidenceId` | 按单次 Agent Request 单调生成 `E1`、`E2`…… | 单次 Request 内稳定且唯一 |

Hash 算法必须在 Manifest 中声明。V1 应使用 SHA-256，不允许使用 Java `hashCode()` 作为持久标识。

### 6.2 VehicleProfile

```text
VehicleProfile
├── vehicleModel
├── modelYear
├── region
├── softwareVersion
├── configurationCode
└── updatedAtMs
```

约束：

- 数据只能来自系统可信 Provider；
- Tool 参数中不得出现这些字段；
- `AgentRequest.extraContext` 不得直接覆盖这些字段；
- Demo 默认值允许配置，但必须明确标识为 Demo；
- V1 未取得真实值时使用：
  - `vehicleModel=DEMO_MODEL`
  - `modelYear=2026`
  - `region=CN`
  - `softwareVersion=DEMO_VERSION`
  - `configurationCode=DEFAULT`
- 任何字段缺失时不得自动扩展到其他车型资料。

### 6.3 DocumentMetadata

```text
DocumentMetadata
├── documentId
├── documentTitle
├── documentType
├── documentVersion
├── language
├── vehicleModel
├── modelYear
├── region
├── softwareVersion
├── configurationCode
├── sourceFormat
├── sourceFileName
├── sourceSha256
├── sourceCharset（HTML/Markdown，可选）
└── pageCount（仅 PDF，可选）
```

V1 `documentType` 至少支持：

- `OWNER_MANUAL`
- `FEATURE_GUIDE`
- `FAULT_GUIDE`
- `WARNING_GUIDE`
- `OTA_RELEASE_NOTE`
- `MAINTENANCE_GUIDE`
- `OTHER_OFFICIAL`

Metadata 来自人工维护的 `corpus.json`，禁止仅根据文件扩展名或文件名猜测车型、版本、文档类型或字符编码。实际文件类型必须与声明的 `sourceFormat` 一致，否则构建失败。

### 6.4 SourceFormat 与 SourceLocator

```text
SourceFormat
├── PDF
├── STATIC_HTML
└── MARKDOWN

SourceLocator
├── sourceFormat
├── headingPath[]
├── pdfPageStart / pdfPageEnd（仅 PDF）
├── printedPageStartLabel / printedPageEndLabel（仅 PDF，可选）
├── htmlElementId（仅 STATIC_HTML，可选）
├── sourceLineStart / sourceLineEnd（仅 MARKDOWN，可选）
└── sectionOrdinal（稳定兜底定位）
```

约束：

- `SourceLocator` 是跨解析器统一值对象，必须随 StructuredBlock、Chunk 和 Evidence 传递；
- 载体自身同时保存 `headingPath` 时，它必须与 `SourceLocator.headingPath` 完全一致，并由 Parser 的同一结构栈生成，禁止分别推断；
- PDF 物理页码从 1 开始；印刷页标签仅在解析或人工配置能够可靠确认时填写；
- HTML 只保留源文件中真实存在、经规范化且在当前文档中唯一的元素 `id`；优先使用覆盖当前内容的标题或 section 锚点，重复 `id` 必须产生诊断并从 Locator 中省略，禁止把临时 CSS Selector、DOM 对象地址或脚本生成锚点作为稳定引用；
- Markdown 行号从 1 开始，只在 Parser 能从源文本位置可靠映射时填写；行号缺失不影响入库；
- `headingPath` 是 HTML/Markdown 的主要用户可见定位，解析器必须在标题缺失时生成稳定的 `sectionOrdinal` 作为内部兜底；
- 用户可见引用必须按 `sourceFormat` 渲染，禁止为 HTML 或 Markdown 伪造页码。

### 6.5 StructuredBlock

离线解析的中间结构统一为：

```text
StructuredBlock
├── blockId
├── blockType
├── sourceLocator
├── headingLevel
├── headingPath[]
├── text
├── table
├── boundingBoxes[]（仅 PDF，可选）
└── extractionConfidence
```

`blockType` 至少包含：

- `HEADING`
- `PARAGRAPH`
- `LIST`
- `WARNING`
- `TABLE`
- `CAPTION`
- `CODE`
- `PAGE_HEADER`
- `PAGE_FOOTER`

`PAGE_HEADER`、`PAGE_FOOTER` 只适用于 PDF，可以参与质量诊断但禁止进入正常 Chunk 正文。HTML/Markdown 的导航、站点页眉页脚和 Front Matter 作为解析诊断记录，不伪装成 PDF Block。

### 6.6 ParentChunk 与 ChildChunk

```text
ParentChunk
├── parentChunkId
├── documentId
├── headingPath[]
├── content
├── sourceLocator
└── childChunkIds[]

ChildChunk
├── chunkId
├── parentChunkId
├── documentId
├── chunkType
├── headingPath[]
├── content
├── embeddingText
├── sourceLocator
├── ordinal
├── tokenEstimate
└── lexicalDocumentLength
```

规则：

- Dense 和 Lexical 检索对象是 Child Chunk；
- Parent Chunk 用于补全语义上下文，不直接参与默认召回；
- Warning 的标题、限制条件和后果应尽量保存在同一 Parent 中；
- Child 不能只包含代词、残缺句或脱离表头无法理解的表格行；
- Chunk 参数必须配置化，并写入 Manifest；
- Chunk 参数调整视为知识库兼容信息变化，必须重新建库。
- Chunk 的 `sourceLocator` 必须由组成它的 StructuredBlock 合并得到，禁止在 Chunk 阶段根据正文猜测来源位置；
- 跨多个 Locator 的 Chunk 必须保留可覆盖其内容的起止范围；无法形成可靠连续范围时必须拆分 Chunk，不能只保留第一个位置。

### 6.7 RetrievalEvidence 与 VehicleKnowledgeEvidence

RAG 内部使用 `RetrievalEvidence` 保存完整检索诊断：

```text
RetrievalEvidence
├── retrievalEvidenceId
├── chunkId
├── parentChunkId
├── content
├── documentId
├── documentTitle
├── documentVersion
├── chapter
├── section
├── sourceLocator
├── denseDistance / denseRank
├── lexicalScore
├── lexicalRank
├── fusionScore
├── fusionRank
├── rerankScore
├── rerankRank
├── retrievalSources[]
└── applicability
```

`applicability` 至少包含：

- `EXACT`：车型及版本精确匹配；
- `COMPATIBLE`：文档声明为通用或兼容；
- `UNKNOWN`：无法证明适用，不能作为可回答证据。

ObjectBox 向量查询的原始值按距离解释，`denseDistance` 越小表示越接近；BM25、RRF 和 Rerank 分数通常按越大越相关解释。两端必须同时保留各阶段 Rank，禁止把 `denseDistance`、`lexicalScore`、`fusionScore` 和 `rerankScore` 合并成一个含义不明的 `relevanceScore`。

模型可见的 `VehicleKnowledgeEvidence` 只保留回答与引用所需字段：

```text
VehicleKnowledgeEvidence
├── evidenceId
├── content
├── documentTitle
├── documentVersion
├── sourceLocator
└── applicability
```

Dense Distance、各阶段 Score/Rank、内部 Chunk ID 和索引 ID 只进入 Trace 与评测，不得序列化到模型可见 ToolResult。

---

## 7. 多格式文档解析与结构恢复协议

### 7.1 统一输入与 Parser Registry

V1 正式支持：

| SourceFormat | 文件 | 处理方式 |
|---|---|---|
| `PDF` | `.pdf` | PDFBox 文本与版面解析，Tabula 文本型表格解析 |
| `STATIC_HTML` | `.html`、`.htm` | 本地静态 DOM 解析，不执行脚本、不加载资源 |
| `MARKDOWN` | `.md` | CommonMark + GFM Table 解析 |

CLI 必须通过统一入口解析：

```text
SourceDocument + DocumentMetadata
        ↓
DocumentParserRegistry
        ↓ select(sourceFormat)
DocumentParser.parse(...)
        ↓
ParseResult<StructuredBlock, ParseDiagnostic>
```

共同约束：

- `sourceFormat` 必须在 `corpus.json` 显式声明，并与扩展名和内容特征一致；
- 输入只能是语料根目录内人工声明的本地普通文件；规范化路径不得逃逸语料根目录，符号链接不得指向目录外；
- 禁止接受 URL、归档文件、设备文件、管道或运行时生成的远程内容；
- 单文件大小、总语料大小、最大 DOM/AST 节点数和最大表格单元格数必须配置化；
- Parser 只负责归一化文档结构，不负责 Chunk、Embedding、检索或 Agent 逻辑；
- Parser 版本、配置 Hash、警告和失败必须写入构建报告及 Manifest；
- 任一格式最终都必须产生同一个 `StructuredBlock`、`TableBlock` 和 `SourceLocator` 协议。

### 7.2 PDF 输入

CLI 必须先将 PDF 分类：

- `TEXT_BASED`：主要内容具有可提取文本层；
- `MIXED`：部分页面有文本，部分页面主要是图片；
- `SCANNED`：主要页面无可用文本层；
- `ENCRYPTED`：需要密码或禁止内容提取；
- `INVALID`：文件损坏或不符合 PDF 结构。

| 类型 | V1 行为 |
|---|---|
| TEXT_BASED | 正常处理 |
| MIXED | 默认构建失败；仅当不可解析页被 `corpus.json` 明确排除并完成人工审核时，才处理剩余页面 |
| SCANNED | 构建失败，返回 `SCANNED_PDF_UNSUPPORTED` |
| ENCRYPTED | 仅当需要密码或当前访问权限禁止内容提取时构建失败，返回 `ENCRYPTED_PDF_UNSUPPORTED`；无密码且 `canExtractContent=true` 的只读 PDF 可继续解析 |
| INVALID | 构建失败，返回 `INVALID_PDF` |

`corpus.json` 对排除页至少记录：PDF 物理页码、排除原因、审核状态、审核人或审核记录标识、审核时间。只有 `reviewStatus=APPROVED` 的页面可以排除；审核必须确认排除不会丢失目标知识、警告、限制或表格正文。未声明、未审核或包含关键知识的不可解析页面均属于硬失败。

PDFBox 负责按页读取 Unicode 文本、保留字符位置和字体信息、恢复阅读顺序、提取页码候选、识别页眉页脚并统计空页和异常字符。PDF 是图形格式，不天然保存标题、段落或阅读顺序，因此禁止只使用一次 `getText()` 输出作为最终结构化语料。

Tabula Java 只负责 PDF 文本型表格：

- `Lattice` 用于有明确边框或网格线的表格；
- `Stream` 用于无边框、依赖文字间距的表格；
- 自动检测不可靠时允许通过文档级配置覆盖策略；
- 表格失败必须进入质量报告，不能静默混入普通段落。

### 7.3 静态 HTML 输入

`STATIC_HTML` 是本地文件解析能力，不是网页构建或网页抓取能力。实现必须满足：

- 只解析输入文件中已经存在的静态 DOM 内容；
- 禁止执行 JavaScript、WebAssembly、事件处理器或任意脚本；
- 禁止启动 Chromium、WebView、Playwright、Selenium 或其他浏览器渲染内核；
- 禁止发起 HTTP/HTTPS 请求，禁止解析或跟踪链接目标；
- 禁止加载外部或本地 CSS、字体、图片、iframe、音视频和其他子资源；
- `<script>`、`<style>`、`<noscript>`、`<template>`、`<iframe>`、`<canvas>`、`<svg>` 和表单控件正文不进入知识语料；
- 链接只保留可见锚文本，`href` 不参与抓取和正文构建；
- 图片只可以保留非空、经过长度限制和文本规范化的 `alt`，不得解析图片二进制；
- 优先从 `<main>`、`<article>` 或 `corpus.json` 指定的内容根节点提取正文；导航、页眉、页脚、侧栏、Cookie 提示等站点噪声通过版本化规则或显式排除 Selector 去除；
- 排除 Selector 和内容根 Selector 只能作用于当前本地 DOM，禁止使用它们触发资源加载或脚本执行；
- HTML 实体必须正确解码，DOM 文本顺序必须保持标题、段落、列表、定义列表、引用、代码块和表格的相对顺序；
- `<h1>` 至 `<h6>` 建立 `headingPath`，源节点真实 `id` 经规范化后写入 `htmlElementId`；无 `id` 时不得生成看似来自源文件的伪锚点；
- `<table>` 按 DOM 行列直接归一化为 `TableBlock`，不经过 Tabula；合并单元格必须展开或产生受控警告，禁止静默错列。

字符集解析必须确定且可测试：`corpus.json` 显式字符集是可信期望值，若与 BOM 或 HTML `<meta charset>` 冲突则构建失败；未显式配置时按 BOM、`<meta charset>`、UTF-8 默认值依次确定。不可逆乱码或主要正文为空时构建失败。文件包含脚本标签本身不代表构建失败，脚本会被移除；如果移除脚本后没有足够静态正文，则返回 `DYNAMIC_HTML_UNSUPPORTED`，不得建设动态渲染兜底。

### 7.4 Markdown 输入

Markdown 语法基线固定为 CommonMark，并仅把 GFM Table 作为 V1 必选扩展。Parser 必须：

- 将 ATX/Setext 标题转为 `headingPath`；
- 保持段落、有序/无序列表、嵌套列表、引用块、分隔线和围栏代码块的结构顺序；
- 将 GFM Table 归一化为 `TableBlock`；
- 链接只保留可见文本，不读取链接目标；
- 图片只保留非空替代文本，不读取本地或远程图片；
- 原始 HTML Block/Inline HTML 必须复用静态 HTML 的安全清理规则，禁止形成第二套 HTML 行为；
- YAML Front Matter 不作为可信 Metadata，不能覆盖 `corpus.json`；若识别并排除，必须记录到解析诊断；
- 在 Parser 能可靠获得源码位置时记录 `sourceLineStart/sourceLineEnd`，否则使用 `headingPath + sectionOrdinal`；
- 空文档、只有 Front Matter、只有链接或只有图片且无替代文本的文档构建失败。

V1 不承诺所有 Markdown 方言。脚注、数学公式、Mermaid、复杂自定义容器等未声明扩展不得被静默解释为官方知识；Parser 应保留可读纯文本或产生 `UNSUPPORTED_MARKDOWN_EXTENSION` 诊断。

### 7.5 统一 TableBlock

```text
TableBlock
├── title
├── headers[]
├── rows[][]
├── sourceLocator
├── extractionMode
└── extractionConfidence
```

`extractionMode` 至少区分：

- `PDF_LATTICE`
- `PDF_STREAM`
- `HTML_DOM`
- `MARKDOWN_GFM`

表格进入 Chunk 时必须转为自包含文本，例如：

```text
表格：空调功能使用条件
列：功能 | 前置条件 | 限制
行：功能=远程制冷；前置条件=车辆已上锁；限制=动力电池电量高于20%。
```

长表格按若干数据行拆分，每个 Child 必须重复表格标题和表头。禁止把单独一行脱离表头写入知识库。HTML/Markdown 表格虽然结构明确，仍必须校验空表头、列数不一致、超大单元格和嵌套表格。

### 7.6 Warning 与限制信息

以下内容必须优先保持语义完整：

- 警告标题；
- 适用条件；
- 禁止事项；
- 风险或后果；
- 解除或恢复条件。

PDF 使用版面、字体和相邻关系识别；HTML 使用标题、语义元素和版本化 class 规则；Markdown 使用标题、引用块和版本化 Admonition 规则。禁止仅根据颜色或 CSS 类名直接断定安全级别。Chunker 不应为满足固定长度而把“操作说明”和紧邻的“警告/限制”完全分离。

### 7.7 解析质量报告

每次构建必须输出 `build-report.json`，至少包含：

- 各 SourceFormat 的文件数、成功数、失败数和 Parser 版本；
- 每个源文件的 SHA-256、字符集、文本字符数和不可识别字符比例；
- 标题、段落、列表、Warning、代码块和表格数量；
- PDF 总页数、成功解析页、空白/疑似扫描页、失败表格和人工批准排除页；
- HTML DOM 节点数、正文根节点、被移除危险/噪声节点数、外部资源引用数、无静态正文和疑似动态页面；
- Markdown AST 节点数、GFM Table 数、Raw HTML 数、Front Matter 和不支持扩展诊断；
- HTML/Markdown 表格结构异常、空正文、过大节点和 Locator 缺失；
- Parent/Child Chunk 数、Embedding 成功/失败数；
- 构建警告、错误和最终是否允许发布。

质量阈值必须配置化并由真实文档验证。在阈值确定前，任何未获批准的 PDF 不可解析页、HTML/Markdown 关键内容缺失、空文档、严重乱码、Embedding 不完整、维度不一致和 Schema 不一致均属于硬失败。`build-report.json` 只有在所有硬门禁通过时才能标记 `publishable=true`。

多格式构建原因码至少包含：

- `SOURCE_FORMAT_MISMATCH`
- `SOURCE_PATH_OUTSIDE_CORPUS`
- `SOURCE_SIZE_LIMIT_EXCEEDED`
- `HTML_CHARSET_CONFLICT`
- `HTML_CONTENT_EMPTY`
- `DYNAMIC_HTML_UNSUPPORTED`
- `MARKDOWN_CONTENT_EMPTY`
- `UNSUPPORTED_MARKDOWN_EXTENSION`
- `TABLE_STRUCTURE_INVALID`
- 既有 PDF 错误码

原因码必须区分硬失败与可审核 Warning；实现不得因新增 HTML/Markdown 而把既有 PDF 硬失败降级成 Warning。

---

## 8. Chunk 协议

### 8.1 Heading-aware

Chunk 必须感知标题层级，保留：

- 文档标题；
- 章；
- 节；
- 子节；
- 表格或 Warning 标题。

`headingPath` 应按从高到低的顺序保存，并用于：

- 生成稳定 ID；
- 形成 Embedding 输入；
- 形成 Evidence 引用；
- Parent/Child 的结构边界；
- 调试解析问题。

Parser 必须尽可能提供结构事实而非仅提供扁平文本：标题层级、列表/步骤连续组、Warning 与表格边界均进入 `StructuredBlock` 或其等价元数据。PDF 无法可靠恢复标题层级时不得伪造 `headingPath`；应产生可审计诊断，并在进入 V2 构建前由人工修复 Parser 或资料边界。

### 8.2 Parent/Child

V2 采用“小节级 Parent + 检索级 Child”。大章节只作为 `headingPath` 的结构 Metadata，绝不因命中一个 Child 而展开为整章。

```text
文档结构 → 最小自然完整小节（Parent）
                         ↓
                    检索定位 Child 1..N
                         ↓
Dense / BM25 / RRF / Rerank 只处理 Child
                         ↓
Child → Parent 去重聚合 → 返回完整 Parent Evidence
```

Parent 的规则：

- 是由明确小节标题界定的最小语义完整小节，主题单一且不跨兄弟小节；
- 理想范围 150～1200 Tokens；1200 为软上限，约 2000 为硬上限；短而完整的小节不得为凑长度与其他小节拼接；
- 超长小节先按独立子主题拆为多个可独立理解的 Parent，保留相同 `sectionPath`、连续分段序号和可靠 Locator；
- Parent 不参与 Dense、HNSW 或 BM25 默认召回，只在 Child 排序完成后批量恢复。

Child 的规则：

- 短 Parent 为 `1 Parent → 1 Child`；长 Parent 为 `1 Parent → N Child`；Child 必须保存 `parentId`、`sectionPath`、Parent 内 `chunkIndex`、类型和 Locator；
- 理想范围 160～320 Tokens，目标约 256，软上限 384，硬上限约 512；语义完整优先于机械长度；
- 默认 overlap 为 0。仅当子主题、段落组、完整列表/步骤、Warning、条件—结果关系和完整句子均无法继续拆分，才允许同一 Parent 内按完整句子做 5%～10% 的长度兜底 overlap；
- 不得切断完整列表项、连续操作步骤、警告的条件/行为/后果、条件—结果关系，以及表格标题/表头/单位与对应数据；不得跨 Parent overlap；
- 不可再分原子单元超出硬上限时必须产生构建诊断，禁止静默截断。

`headingPath`（即 `sectionPath`）必须随 Parent 和 Child 进入 Embedding、BM25、Rerank、Evidence 与引用；Child 正文可以精准定位，完整 Parent 才负责恢复可回答的上下文。

### 8.3 Chunk 大小

Chunk 大小、Overlap、Parent 最大长度和表格每片行数必须：

- 在 CLI 配置中集中定义；
- 写入 Manifest；
- 禁止散落为多个硬编码常量；
- 通过真实文档和检索评测确定；
- 调整后重新生成全部 Embedding 和索引。

上述 V2 数值是 `TEST_ONLY` 初始基线，不是未经校准的正式质量阈值。Token 必须由 CLI 和 Android 共用同一版本化估算算法及 Golden；字符/字节上限仅可作为网络、Binder 或内存硬保护，不能替代语义 Token 预算。任何 Chunk 规则变更必须重建 Parent/Child ID、Embedding、BM25、ObjectBox Bundle 并重新执行 Eval V2。

---

## 9. Embedding 协议

### 9.1 固定配置

V1 固定：

| 参数 | 值 |
|---|---|
| Provider | DashScope |
| Model | `text-embedding-v4` |
| Dimension | `1024` |
| Output | Dense Float Vector |
| ObjectBox Distance | `COSINE` |

文档 Embedding 和查询 Embedding 必须使用相同模型、维度和预处理版本。

### 9.2 Embedding 输入

仅 Child 参与 Embedding。Child 的 Embedding 输入必须由确定性模板生成：

```text
文档：{documentTitle}
位置：{headingPath}
类型：{chunkType}
内容：{content}
```

车型、地区、软件版本等 Metadata 主要用于可信过滤，不应通过模型生成，也不应仅依赖 Embedding 判断适用性。

### 9.3 向量校验

CLI 写库前必须校验：

- 维度恰好为 1024；
- 不包含 `NaN`；
- 不包含正负无穷；
- 每个可检索 Child 只有一个有效向量；
- API 返回顺序与 Chunk 输入顺序正确对应；
- 任一 Child Embedding 失败时不得发布“完整成功”的数据库。

### 9.4 模型升级

更换模型、维度、输出类型、距离算法或 Embedding 输入模板时，必须：

1. 修改 Embedding 协议版本；
2. 全量重新计算向量；
3. 全量重建 HNSW；
4. 提升 Bundle 版本；
5. 更新 Manifest；
6. 重新执行跨端兼容和检索评测。

禁止在同一个 HNSW 属性中混用不同模型或维度的向量。

---

## 10. ObjectBox 数据协议

### 10.1 Store 定位

RAG 使用独立命名的 ObjectBox Store，仅存放车辆知识数据。它不替换现有 Conversation、Session、ChatMemory 和长期记忆使用的 SQLite 数据库。

Android 运行时不得把用户数据、会话数据或动态车辆状态写入 RAG Store。

### 10.2 KnowledgeStoreMetadataEntity

建议字段：

```text
KnowledgeStoreMetadataEntity
├── id: long
├── bundleId
├── bundleVersion
├── knowledgeScopeId
├── formatVersion
├── schemaFingerprint
├── builderVersion
├── objectBoxVersion
├── embeddingProvider
├── embeddingModel
├── embeddingDimension
├── distanceType
├── hnswConfigFingerprint
├── embeddingTemplateVersion
├── lexicalAnalyzerVersion
├── sourceLocatorSchemaVersion
├── parserConfigHash
├── supportedSourceFormats
├── sourceFormatCounts
├── chunkingConfigHash
├── corpusHash
├── documentCount
├── parentChunkCount
├── childChunkCount
├── lexicalTermCount
├── averageLexicalDocumentLength
└── builtAtEpochMs
```

数据库中只能存在一个当前 Store Metadata 记录。`supportedSourceFormats` 和 `sourceFormatCounts` 使用稳定字段或版本化、确定性排序的 JSON 序列化，不得依赖 Java Set/Map 的迭代顺序；其声明和统计必须与 KnowledgeDocumentEntity 实际内容一致。

### 10.3 KnowledgeDocumentEntity

建议字段：

```text
KnowledgeDocumentEntity
├── id: long
├── documentId: String（唯一索引）
├── documentTitle
├── documentType
├── documentVersion
├── language
├── vehicleModel
├── modelYear
├── region
├── softwareVersion
├── configurationCode
├── sourceFormat
├── sourceFileName
├── sourceSha256
├── sourceCharset
└── pageCount: int（PDF > 0；其他格式为 0）
```

### 10.4 KnowledgeChunkEntity

Parent 与 Child 可以使用同一 Entity，通过 `chunkLevel` 区分：

```text
KnowledgeChunkEntity
├── id: long
├── chunkId: String（唯一索引）
├── chunkLevel: PARENT / CHILD
├── parentChunkId
├── documentId
├── documentTitle
├── documentVersion
├── documentType
├── language
├── vehicleModel
├── modelYear
├── region
├── softwareVersion
├── configurationCode
├── chunkType
├── headingPath
├── chapter
├── section
├── content
├── sourceFormat
├── pdfPageStart / pdfPageEnd
├── printedPageStartLabel / printedPageEndLabel
├── htmlElementId
├── sourceLineStart / sourceLineEnd
├── sectionOrdinal
├── ordinal
├── tokenEstimate
├── lexicalDocumentLength
└── embedding: float[1024] + HNSW(COSINE)
```

规则：

- Parent 的 `embedding` 为空；
- Child 的 `embedding` 必须有效；
- `parentChunkId` 是 Child 到 Parent 的稳定映射；Parent 自身不填写该字段；
- `headingPath` 承载版本化 `sectionPath`；Child 的 `ordinal` 表示同 Parent 内 `chunkIndex`，Parent 的 `ordinal` 表示文档内稳定顺序；
- 为避免向量召回后再跨实体读取过滤字段，Child 应冗余可信 Metadata；
- 冗余 Metadata 必须从 DocumentMetadata 复制，禁止二次推断；
- `headingPath` 可以使用稳定分隔符序列化，分隔规则必须版本化。
- `SourceLocator` 在 ObjectBox Entity 中可以扁平化存储，但映射回领域模型时必须恢复格式互斥语义；例如 HTML Chunk 禁止携带 PDF 页码；
- HTML/Markdown Locator 字段允许为空时，必须由 `headingPath + sectionOrdinal` 提供稳定兜底；
- 扁平化数值字段使用 `0` 表示“不适用于当前格式”，字符串使用空值；领域模型映射后必须转换为 Optional/空字段，禁止把 `0` 渲染成用户可见页码或行号；
- 同一个 Store 中的所有 Document 和 Chunk 必须属于 Manifest 声明的同一 `knowledgeScopeId`；文档中的 `*` 只表示其内容经过审核后兼容该 Scope，不表示该 Store 可以服务任意车型。

### 10.5 LexicalTermEntity

V1 使用本地倒排索引实现 BM25，不把 ObjectBox `contains()` 当作 BM25。

```text
LexicalTermEntity
├── id: long
├── term: String（唯一索引）
├── documentFrequency: int
├── chunkEntityIds: long[]
└── termFrequencies: int[]
```

约束：

- `chunkEntityIds` 与 `termFrequencies` 长度必须一致；
- 每个位置表示该 term 在对应 Child 中的出现次数；
- postings 只引用同一个 `data.mdb` 内部的 Child Entity ID；
- 重建数据库时 postings 必须同步全量重建；
- Parent 不进入 Lexical postings；
- 平均文档长度由 Store Metadata 提供；
- BM25 参数由 Android 检索配置集中管理并记录到 Trace。

ObjectBox 支持基础类型数组，因此 postings 可以直接使用 `long[]` 和 `int[]`，避免运行时解析大段 JSON。

### 10.6 预构建数据库

离线 CLI 最终输出：

```text
output/
├── data.mdb
├── manifest.json
└── build-report.json
```

只有 `data.mdb` 和 `manifest.json` 进入 APK。`build-report.json` 作为构建审计产物保存在离线输出或项目测试资料中。

---

## 11. Manifest 交付协议

### 11.1 文件位置

APK 内固定位置：

```text
app/src/main/assets/rag/knowledge_db/data.mdb
app/src/main/assets/rag/knowledge_db/manifest.json
```

### 11.2 Manifest 示例

```json
{
  "formatVersion": 1,
  "bundleId": "vehicle-knowledge-cn",
  "bundleVersion": "2026.07.21.1",
  "knowledgeScopeId": "demo-model-2026-cn-default-demo-version",
  "builderVersion": "1.0.0",
  "builtAtEpochMs": 1784563200000,
  "objectBoxVersion": "<pinned-version>",
  "schemaFingerprint": "sha256:<schema-hash>",
  "sourceLocatorSchemaVersion": 1,
  "dataFile": {
    "name": "data.mdb",
    "sizeBytes": 0,
    "sha256": "<data-file-hash>"
  },
  "embedding": {
    "provider": "DashScope",
    "model": "text-embedding-v4",
    "dimension": 1024,
    "distanceType": "COSINE",
    "templateVersion": 1
  },
  "hnsw": {
    "configFingerprint": "sha256:<hnsw-config-hash>",
    "neighborsPerNode": "<pinned-value>",
    "indexingSearchCount": "<pinned-value>",
    "vectorCacheHintKb": "<pinned-value>",
    "flags": ["<pinned-value>"]
  },
  "scope": {
    "vehicleModel": "DEMO_MODEL",
    "modelYear": "2026",
    "region": "CN",
    "softwareVersion": "DEMO_VERSION",
    "configurationCode": "DEFAULT"
  },
  "parsers": {
    "configHash": "sha256:<parser-config-hash>",
    "supportedSourceFormats": ["PDF", "STATIC_HTML", "MARKDOWN"],
    "pdf": {
      "pdfBoxVersion": "<pinned-version>",
      "tabulaVersion": "<pinned-version>"
    },
    "html": {
      "implementation": "<pinned-parser>",
      "version": "<pinned-version>",
      "mode": "STATIC_DOM_ONLY",
      "networkAccess": false,
      "scriptExecution": false
    },
    "markdown": {
      "implementation": "<pinned-parser>",
      "version": "<pinned-version>",
      "syntax": "COMMONMARK",
      "extensions": ["GFM_TABLE"]
    }
  },
  "lexical": {
    "analyzerVersion": 1,
    "languages": ["zh", "en"],
    "algorithm": "BM25",
    "tokenization": "CJK_BIGRAM_TRIGRAM_WITH_EXACT_LATIN"
  },
  "chunking": {
    "configVersion": 1,
    "configHash": "sha256:<chunk-config-hash>"
  },
  "corpus": {
    "corpusHash": "sha256:<corpus-hash>",
    "documentCount": 0,
    "sourceFormatCounts": {
      "PDF": 0,
      "STATIC_HTML": 0,
      "MARKDOWN": 0
    },
    "parentChunkCount": 0,
    "childChunkCount": 0,
    "lexicalTermCount": 0
  }
}
```

`<pinned-version>`、`<pinned-parser>`、`<pinned-value>` 和统计值只能由前置验证与真实构建生成，禁止把示例占位值作为正式 Manifest 发布。HNSW 参数名称应映射到最终锁定 ObjectBox 版本提供的实际配置项；不适用的字段必须移除，禁止伪造支持能力。

### 11.3 兼容校验

Android 激活数据库前必须校验：

- `formatVersion` 可识别；
- `bundleId` 与应用预期一致；
- `bundleVersion` 非空；
- `knowledgeScopeId` 非空且与 `scope` 内容映射一致；
- `data.mdb` 文件大小一致；
- `data.mdb` SHA-256 一致；
- ObjectBox Schema Fingerprint 一致；
- ObjectBox 版本属于应用支持范围；
- Embedding 模型、维度和距离类型一致；
- HNSW 配置指纹与 Android 编译期模型一致；
- `sourceLocatorSchemaVersion` 可识别；
- `supportedSourceFormats` 只包含 Android 当前支持渲染的格式；
- Parser 配置 Hash 与数据库内 Metadata 一致；
- Lexical Analyzer 版本可识别；
- 数据库内 Metadata 与 Manifest 一致；
- Document 总数、各 SourceFormat 数量、Parent/Child/Term 数量一致；
- 数据库能够由当前 `MyObjectBox` 模型成功打开。

任一硬校验失败时禁止激活新数据库。

---

## 12. Android 数据库安装与生命周期协议

### 12.1 安装不是重新建库

Android 首次使用只执行预构建数据库的复制和激活：

```text
APK Asset data.mdb
        ↓ copy
filesDir/rag/staging/{bundleVersion}/data.mdb
        ↓ validate
ObjectBox 试打开 + 统计核验
        ↓ close
原子切换 Active Store
```

Android 禁止执行：

- PDF 解析；
- HTML DOM 解析或 Markdown AST 解析；
- 批量文档 Embedding；
- Chunk 生成；
- BM25 建索引；
- HNSW 全量建库。

复制前必须进行空间门禁。安装器应根据 Manifest 的 `dataFile.sizeBytes`、当前 Store 保留需求、staging 临时空间和运行安全余量计算所需空间；空间不足时返回结构化失败并保留当前 Store，不得边复制边删除旧库。固定安全余量和计算公式由 Android 实施计划给出并通过目标车机验证。

Android 实施计划必须明确 `.mdb` Asset 的打包策略：是否配置 `noCompress` 应由 APK 体积、安装速度和复制性能实测决定；无论 Asset 是否压缩，安装器都必须通过流式读取接口复制，不得假定能够直接获得可随机访问的 Asset 文件路径或把 Asset 中的 Store 原地打开。

### 12.2 推荐目录

```text
filesDir/rag/
├── active.json
├── stores/
│   ├── {bundleVersion}/data.mdb
│   └── ...
└── staging/
```

`active.json` 只保存当前激活版本和校验信息，不保存知识正文。

### 12.3 原子切换

安装流程必须满足：

1. 先复制到 staging；
2. 复制过程中不得覆盖当前数据库；
3. Hash 和 Schema 校验通过后试打开；
4. 试打开成功后关闭临时 Store；
5. 再切换 active 指针；
6. 新数据库激活失败时继续保留旧版本；
7. Store 正在使用时不得替换其底层文件；
8. 清理旧版本必须在确认无 Store 引用后执行。
9. 复制应以流式方式同时计算 Hash，避免把完整数据库读入内存；
10. active 指针与数据库目录切换前必须完成必要的落盘操作；
11. Service 或进程在复制、校验、试打开、切换任一阶段被终止后，下次启动必须能够识别并清理未完成 staging，或恢复到最后一个已确认 Active Store；
12. APK Asset、当前 Store 和新 staging 同时存在时必须纳入峰值存储评估。

### 12.4 初始化状态

知识服务至少有：

- `UNINITIALIZED`
- `INSTALLING`
- `READY`
- `FAILED`

安装不应阻塞普通聊天和车辆控制。知识 Tool 在 `INSTALLING` 时返回结构化 `KNOWLEDGE_STORE_INITIALIZING`，不得抛出未处理异常。

---

## 13. 可信车辆 Metadata 协议

### 13.1 Provider 抽象

```java
public interface VehicleProfileProvider {
    VehicleProfile currentProfile();
}
```

V1 使用：

```text
VehicleStateMachineVehicleProfileProvider
                    ↓
            VehicleStateMachine
```

未来接入真实车辆时可以替换为 SOA Provider，RAG 检索核心不应变化。

### 13.2 Knowledge Scope 解析

`KnowledgeScopeResolver` 根据可信 `VehicleProfile` 和应用内版本化映射生成期望的 `knowledgeScopeId`。该标识不是 Tool 参数，用户、模型和 `extraContext` 均不得覆盖。

V1 强制执行“一份 Bundle 一个 Scope”：

- Active Store 必须且只能声明一个 `knowledgeScopeId`；
- Store 内所有资料必须在构建期证明适用于该 Scope；
- 可以收录通用资料，但其通用性必须由 `corpus.json` 人工声明和审核；
- 不允许在同一个 Store 中通过过滤字段划分多个车型或软件版本的独立语料池；
- 当前 `VehicleProfile` 无法解析 Scope，返回 `PROFILE_INCOMPLETE`；
- 解析出的 Scope 与 Active Store 不一致，返回 `KNOWLEDGE_SCOPE_MISMATCH`；
- Scope 不匹配时禁止退回旧版本、其他车型或其他地区资料作答。

V1 随 APK 更新知识库。如果真实车辆软件版本已变化而 APK 尚未携带匹配 Scope，知识请求必须受控失败；普通聊天和车控能力不受影响。

### 13.3 Metadata Eligibility

候选证据只有满足以下条件才能进入融合与最终 Evidence：

- `vehicleModel` 等于当前值，或文档明确声明 `*`；
- `modelYear` 等于当前值，或文档明确声明 `*`；
- `region` 等于当前值，或文档明确声明 `*`；
- `softwareVersion` 等于当前值，或文档明确声明 `*`；
- `configurationCode` 等于当前值，或文档明确声明 `*`。

V1 不对任意版本字符串做未经定义的大小比较。需要版本范围时，必须先扩展 Metadata 协议和兼容规则。

### 13.4 UNKNOWN 行为

如果当前 VehicleProfile 的关键字段缺失：

- 只允许召回对应字段为 `*` 的通用资料；
- 禁止把其他具体车型或版本资料作为兜底；
- 无通用资料时返回 `PROFILE_INCOMPLETE` 或 `NO_EVIDENCE`；
- 不允许模型猜测当前车型。

### 13.5 过滤实现语义

“Metadata Filter”是强制资格边界，不要求绑定某一种 ObjectBox 查询 API：

- 如果选定 ObjectBox 版本验证支持 HNSW 条件与 Metadata 条件安全组合，可以在数据库查询阶段过滤；
- 如果无法保证组合查询的召回语义，Dense 检索必须扩大候选数后执行严格后过滤；
- 无论采用哪种实现，不满足 Metadata 的候选都禁止进入 RRF、Rerank 和 Evidence；
- 过滤前后候选数量必须进入 Trace。

单 Scope 限制是 V1 的第一层隔离，Document Metadata Eligibility 是第二层证据资格校验。后者不能被用于绕过或模拟多 Scope Store。

---

## 14. Lexical/BM25 协议

### 14.1 Analyzer

V1 Analyzer 同时支持中文和英文：

- 中文连续文本生成 bigram 和 trigram；
- 英文字母统一小写；
- `ACC`、`ESP`、`AUTO HOLD` 等保留可精确匹配 Token；
- 故障码、版本号和字母数字组合保留完整 Token；
- Unicode 兼容标准化和空白规范化必须在离线与在线两端一致；
- 停用词策略、标点规则和数字规则必须版本化；
- Analyzer 变化必须提升 `lexicalAnalyzerVersion` 并重新建索引。

### 14.2 BM25

Android 使用离线 postings 和 Child 的 `lexicalDocumentLength` 计算 BM25。词法文档由版本化渲染器产生，至少包含 `sectionPath + Child 正文`；Parent 不参与 BM25。

要求：

- `N` 为可检索 Child 总数；
- `df` 来自 `LexicalTermEntity.documentFrequency`；
- `avgdl` 来自 Store Metadata；
- `tf` 来自 postings；
- `k1`、`b` 必须集中配置；
- 初始参数由 Android 实施计划给出，并通过评测调整；
- BM25 原始分数只能作为 Lexical 排名和诊断使用，不能与 Dense 分数直接相加。

由于 V1 一份 Store 只对应一个 `knowledgeScopeId`，`N`、`df` 和 `avgdl` 均按该 Scope 的完整可检索语料计算。禁止在包含多个独立 Scope 的全局统计上先计算 BM25、再按车辆过滤，否则 IDF 与长度归一化口径不成立。

### 14.3 离线降级

Query Embedding 因无网、超时或服务错误失败时：

- 如果 Lexical 有合格结果，返回 `DEGRADED` + `LEXICAL_ONLY`；
- 如果 Lexical 也无合格结果，返回 `NO_EVIDENCE`；
- 不得因为 Dense 不可用而把 Lexical 阈值降为无条件接受；
- 最终回答应当基于 Evidence，不需要向用户暴露内部算法错误。

---

## 15. Hybrid Retrieval 协议

### 15.1 标准流程

```text
Query
  ↓
长度、Deadline、取消和重复检查
  ↓
QueryNormalizer + LexicalAnalyzer
  ↓
Trusted VehicleProfile
  ↓
Lexical BM25 Top-K
  +
DashScope Query Embedding → ObjectBox Dense Top-K
  ↓
Metadata Eligibility Filter
  ↓
RRF Candidate Fusion（按 Chunk ID 合并）
  ↓
完全重复 / 高度相似 Child 去重
  ↓
qwen3-rerank（仅 Child）
  ↓
Child → Parent 映射、Parent 聚合去重
  ↓
批量读取完整 Parent
  ↓
完整 Parent Evidence 预算与 retrievalConfidence
  ↓
RagResult
```

### 15.2 初始检索参数

V1 初始基线：

| 参数 | 初始值 |
|---|---:|
| Query 最大长度 | 512 字符 |
| Dense Top-K | 20（Eval 可比较至 30） |
| Lexical Top-K | 20（Eval 可比较至 30） |
| RRF Candidate | 最多 30 Child |
| Child Rerank | 默认 15，最多 20 |
| Final Evidence | 通常 2～3 个 Parent，最多 4 个 |
| Rerank 候选预算 | 初始 6000 Tokens，硬保护不超过 7000 Tokens |
| 模型侧 Evidence 预算 | 初始 5000 Tokens；仅接收完整 Parent |
| ToolResult 序列化硬上限 | 独立字符/字节上限，仅用于传输保护 |
| 单请求最多 RAG 调用 | 2 |
| Fusion | RRF |
| RRF `k` | 60 |

以上属于评测前基线。实现必须配置化，禁止散落硬编码。调整检索参数不必改变数据库格式，但必须记录配置版本和评测结果。

### 15.3 RRF

RRF 使用排名而不是直接相加异构分数：

```text
RRF(chunk) = Σ 1 / (rrfK + rank_i(chunk))
```

要求：

- Dense 和 Lexical 分别生成稳定排名；
- 同一 Chunk 在多路出现时合并；
- 只出现在一路的 Chunk仍可进入候选；
- 分数相同时使用确定性 Tie-breaker；
- RRF 前必须排除不适用 Metadata；
- `retrievalSources` 记录候选来自 Dense、Lexical 或两者。
- RRF 后必须先做完全重复和高度相似 Child 去重，并记录删除原因；同一 Parent 的多个 Child 可以进入 Rerank，但最终不能重复占用 Parent Evidence 名额；
- 高度相似阈值必须由 Eval DEV 集校准。若可能删除人工确认互补的 Child，必须暂停并人工复核，不能用经验值静默删除。

### 15.4 Rerank

Rerank 使用 DashScope `qwen3-rerank`：

- 输入为规范化 Query 和最多 15 个融合后的 Child；
- 每个候选严格为 `sectionPath + Child 正文`，不得加入相邻 Child、Parent 正文或章节级上下文；
- Rerank 候选 Token 预算与最终模型侧 Evidence Token 预算必须分别计算；
- `top_n` 由系统侧固定，模型 Tool 参数不能修改；
- Instruction 使用固定、版本化的检索指令；
- Rerank Score 单独保留；
- Rerank 超时或失败时允许使用 RRF 降级；
- Rerank 返回索引必须校验范围和唯一性。

### 15.5 Parent Evidence

Rerank 后才执行 Child → Parent 映射，避免把完整 Parent 发送给 Reranker：

- 按 Rerank 后 Child 顺序聚合 `parentId`，同一 Parent 最终只保留一次；
- Parent 主排序取其最高 Child Rerank Score；同分时依次以最佳 Child 的 RRF Rank、Parent ID 稳定排序；Rerank 降级时以最佳 Child Fusion Rank 代表排序；
- Store 必须批量读取 Parent，禁止逐个 Parent N 次查询；缺失 Parent 必须 fail closed 并产生稳定原因码；
- 最终 Evidence 的 `content` 必须是完整 Parent，不得再返回裸 Child，也不得补相邻 Child；
- 按排序加入完整 Parent，通常 2～3 个、最多 4 个；加入下一个 Parent 超过 5000 Token 时停止，不允许截断 Parent；第一名 Parent 已超预算表示构建/配置异常，必须受控失败或诊断；
- Parent 的 `retrievalConfidence` 是“检索相关性”而非“答案正确率”。它以最佳 Child Score、与下一 Parent 的 Margin、Rerank/RRF_FALLBACK 来源和降级状态为特征；未完成 Eval 校准前模型侧只输出 `UNASSESSED`，原始特征仅进入内部诊断和受控 Trace。

### 15.6 Answerable

`answerable=true` 必须同时满足：

- 至少有一个 Metadata 合格 Evidence；
- Evidence 达到当前检索模式对应的可靠性阈值；
- Evidence 内容非空且来源完整；
- 没有硬失败；
- 当前请求未取消、未超过 Deadline。

阈值必须由评测确定并配置化。不得在缺少评测时伪造一个通用分数阈值。

---

## 16. 内部 RagResult 与模型侧 ToolResult 协议

### 16.1 状态枚举

```text
RagStatus
├── SUCCESS
├── NO_EVIDENCE
├── DEGRADED
├── TIMEOUT
├── CANCELLED
└── ERROR
```

典型失败原因：

- `KNOWLEDGE_STORE_INITIALIZING`
- `KNOWLEDGE_STORE_UNAVAILABLE`
- `PROFILE_INCOMPLETE`
- `KNOWLEDGE_SCOPE_MISMATCH`
- `QUERY_EMPTY`
- `QUERY_TOO_LONG`
- `DUPLICATE_QUERY`
- `MULTIPLE_RAG_CALLS_IN_ITERATION`
- `INVOCATION_LIMIT_REACHED`
- `EMBEDDING_UNAVAILABLE`
- `RERANK_UNAVAILABLE`
- `NO_MATCHING_METADATA`
- `NO_RELIABLE_EVIDENCE`
- `REQUEST_DEADLINE_EXCEEDED`
- `REQUEST_CANCELLED`
- `INTERNAL_RETRIEVAL_ERROR`

### 16.2 内部 RagResult

`RagResult` 只在 RAG Service、策略、Trace 和评测组件之间流转，不直接序列化给主模型：

```text
RagResult
├── schemaVersion
├── status
├── answerable
├── query
├── normalizedQuery
├── retrievalMode
├── retrievalEvidence[]
├── degradedReasons[]
├── failureReasonCode
├── elapsedMs
└── diagnosticsSummary
```

`retrievalMode` 至少包含：

- `HYBRID_RERANKED`
- `HYBRID_FUSION_ONLY`
- `LEXICAL_ONLY`

内部 `RetrievalEvidence` 的正文是完整 Parent；它可以携带 parentId、bestChildId、supportingChildIds、Dense/BM25/RRF/Rerank 分数与 Rank、rankingSource、confidence features。`diagnosticsSummary` 禁止包含异常堆栈或凭证。

### 16.3 模型侧 VehicleKnowledgeToolResult

Tool 适配层必须把内部 `RagResult` 映射为精简的 `VehicleKnowledgeToolResult`：

```text
VehicleKnowledgeToolResult
├── schemaVersion
├── status
├── answerable
├── query
├── evidence[]: VehicleKnowledgeEvidence
├── degradedReasons[]
├── failureReasonCode
└── userSafeMessage
```

映射时必须移除：

- `normalizedQuery`；
- `elapsedMs` 和阶段耗时；
- `retrievalMode`；
- 内部 Chunk、Parent 和 ObjectBox Entity ID；
- Dense Distance 与所有 Score/Rank；
- 内部候选、阈值和诊断摘要。

这些字段仍可按第 20 节的隐私规则进入 Trace 和离线评测。

### 16.4 ToolResult JSON 示例

```json
{
  "schemaVersion": 1,
  "status": "SUCCESS",
  "answerable": true,
  "query": "AUTO HOLD在什么条件下不可用",
  "evidence": [
    {
      "evidenceId": "E1",
      "content": "...",
      "documentTitle": "车辆用户手册",
      "documentVersion": "1.0",
      "sourceLocator": {
        "sourceFormat": "PDF",
        "headingPath": ["驾驶辅助", "AUTO HOLD"],
        "pdfPageStart": 86,
        "pdfPageEnd": 86,
        "printedPageStartLabel": "82",
        "printedPageEndLabel": "82",
        "htmlElementId": null,
        "sourceLineStart": null,
        "sourceLineEnd": null,
        "sectionOrdinal": 12
       },
       "applicability": "EXACT",
       "retrievalConfidence": "UNASSESSED"
    }
  ],
  "degradedReasons": [],
  "failureReasonCode": null,
  "userSafeMessage": null
}
```

`evidenceId` 必须在单次 Agent Request 中稳定、唯一并采用便于模型引用的短标识，例如 `E1`、`E2`。编号由请求级 `KnowledgeRequestState` 单调分配：如果第一次 ToolResult 使用 `E1` 至 `E3`，第二次 ToolResult 必须从 `E4` 继续，禁止重新从 `E1` 开始。它映射到内部稳定 `retrievalEvidenceId`，但不得暴露数据库内部 ID。

### 16.5 输出约束

- ToolResult 必须是稳定 JSON，不返回 Java `toString()`；
- JSON Schema 版本必须显式携带；
- 对模型可见的错误应是受控原因码和简洁说明；
- 禁止把内部异常堆栈写入 ToolResult；
- Evidence 必须经过数量、长度和重复控制；
- 每个 Evidence 都是完整 Parent，并携带 `sectionPath` 与稳定的 `retrievalConfidence` 枚举（`HIGH` / `MEDIUM` / `LOW` / `UNASSESSED`）；该枚举不表示事实正确概率；
- `answerable=false` 时不得携带看似可直接作答但未达阈值的正文；
- `DEGRADED` 可以 `answerable=true`，但必须确有合格 Evidence。
- ToolResult 的正文预算必须按 Token 估算；字符或字节上限仅作为 Binder/JSON/日志传输保护，不能替代 Context Token 预算；
- 内部 `RagResult` 与模型侧 `VehicleKnowledgeToolResult` 必须使用不同类型，禁止通过同一 DTO 的序列化忽略列表来维持边界。

---

## 17. Agent 接入协议

### 17.1 Tool 签名

模型仅允许提供 Query：

```text
searchVehicleKnowledge(query)
```

模型禁止提供：

- 车型；
- 年款；
- 地区；
- 软件版本；
- 配置编码；
- Top-K；
- Rerank 开关；
- Evidence 数量；
- Deadline；
- 是否允许跨车型。

这些字段全部由可信系统和策略侧决定。

### 17.2 ToolGroup

新增：

```text
VEHICLE_KNOWLEDGE_GROUP
└── searchVehicleKnowledge
```

知识 Tool 应作为低动作风险的只读工具，但“低动作风险”不表示可以绕过本轮工具授权或数据边界。

### 17.3 KnowledgeRequirement

```text
KnowledgeRequirement
├── NONE
├── OPTIONAL（V1 保留值，生产 Detector 不输出）
└── REQUIRED
```

典型 REQUIRED 请求：

- 故障码、告警灯、提示文本；
- 官方使用条件；
- 功能限制或注意事项；
- 车型/版本差异；
- “为什么该功能不可用”；
- 明确要求查用户手册或官方资料。

V1 使用轻量 `KnowledgeNeedDetector` 叠加在现有 Intent 结果之上，不创建与全局 IntentRouter 竞争的第二套路由系统。

V1 生产行为只允许：

- `NONE`：沿用原 ToolGroup 选择结果，不开放知识 Tool；
- `REQUIRED`：将本轮能力集合收敛为仅包含 `VEHICLE_KNOWLEDGE_GROUP`；
- `OPTIONAL`：枚举和协议解析可以保留，但 Detector、Runtime 和 ToolGroupSelector 不得主动产生该状态。

在统一 Capability Router、复合任务时序和 Deadline 分配策略完成前，禁止把 OPTIONAL 实现为“知识 Tool 与其他 Tool 的简单并集”。

### 17.4 REQUIRED 行为

对于 REQUIRED：

1. 本轮必须且只能开放 `searchVehicleKnowledge`；
2. 模型未调用知识 Tool 时允许一次强制重试；
3. 第二次仍未调用时返回 `KNOWLEDGE_TOOL_NOT_CALLED`；
4. Tool 返回 `answerable=false` 时禁止模型自由生成知识结论；
5. Tool 返回可靠 Evidence 后，最终回答必须以 Evidence 为依据；
6. 最终回答必须引用 ToolResult 中真实存在的 Evidence ID，来源文字由 `CitationGuard` 渲染；
7. 不得把模型预训练知识冒充为车辆官方说明；
8. 当前 Agent 迭代只允许一个 RAG Tool Call，禁止并行批量调用；
9. 模型在同一迭代请求多个 RAG Tool、请求其他 Tool 或混合请求 RAG 与其他 Tool 时，整批不得进入 Dispatcher，并返回受控协议错误；
10. 第二次 RAG Query 只能发生在模型已经收到第一次 ToolResult 的后续迭代。

无 Evidence 的确定性用户提示应由策略控制，例如：

> 当前车辆资料中没有找到足够可靠的依据，暂时无法确认。

### 17.5 Tool 执行授权

RAG 接入前或同时，TEXT Tool 执行必须增加本轮允许集合复核：

```text
ToolExecutionRequest.name
        ↓
是否属于本轮 ContextAssemblyResult.toolSpecifications
        ├── 是：进入 Safety / Dispatch
        └── 否：拒绝并写回 TOOL_NOT_AUTHORIZED
```

该规则适用于全部 Tool，不只适用于 RAG。Tool 可见性与 Tool 执行授权必须形成闭环。

REQUIRED 请求的授权集合只有 `searchVehicleKnowledge`。即使全局 `ToolRegistry` 已注册其他 Tool，也不得据此视为本轮获得执行权限。授权校验必须发生在 Safety 和反射 Dispatch 之前。

### 17.6 调用次数和去重

单 Request：

- 最多 2 次 RAG 调用；
- Query 在 Unicode/空白/大小写规范化后计算 SHA-256；
- 相同 Query 禁止重复执行；
- 第二次查询必须与第一次具有实质差异；
- 第二次查询只在第一次证据不足且 Deadline 允许时执行；
- 调用状态属于请求级，禁止使用跨请求全局可变计数器；
- 请求结束必须释放状态；

调用规则由请求级 `KnowledgeRequestState` 承载，至少包含：

```text
KnowledgeRequestState
├── requirement
├── totalInvocationCount
├── currentIterationInvocationCount
├── normalizedQueryHashes[]
├── lastEvidenceStatus
├── degradedMode
├── nextEvidenceOrdinal
└── citationEvidenceMap
```

该状态应随 `RequestSession` 或 `RequestExecutionContext` 生命周期存在，不得使用无清理保证的全局 Map。进入新 Agent 迭代时重置 `currentIterationInvocationCount`，但保留请求级总次数、Query Hash、上一轮 Evidence 状态、Evidence 编号和引用映射。`citationEvidenceMap` 只保存当前请求允许引用的 Evidence 映射，请求结束必须释放。

### 17.7 V1 复合请求边界

V1 不承诺一次请求中完成：

> 查询为什么功能不可用，并立即执行车辆控制。

遇到此类请求，必须拒绝在同一请求中执行车辆动作，并要求用户拆分任务。系统可以明确说明将先完成知识查询，但不得在同一轮隐式继续车控；即使模型产生车控 Tool Call，也会被本轮 Allowlist 拒绝。

---

## 18. Deadline、取消与降级协议

### 18.1 绝对 Deadline

KnowledgeRequirement 为 REQUIRED 的请求使用 60 秒端到端绝对 Deadline，语义与现有前向视野请求一致。`OPTIONAL` 在 V1 不进入生产运行链：

- Service、Runtime、Context、AgentLoop、RAG、Embedding、Rerank 共用同一个绝对截止时间；
- 任何组件禁止重新启动独立的完整 60 秒计时器；
- RAG 根据剩余时间决定是否执行 Dense、Rerank 或第二次查询；
- 必须为最终主模型回答预留时间；
- 各阶段子预算必须配置化并通过延迟评测确定。

### 18.2 可取消云调用

Query Embedding 和 Rerank 必须使用可取消 HTTP Call，并注册到现有请求调用注册机制：

- 用户取消时取消当前云调用；
- Service Timeout 时取消当前云调用；
- 注册晚于取消标记时必须立即取消；
- 调用结束及时注销；
- V1 云调用按顺序执行，避免单 requestId 同时注册多个 Call；
- 不得吞掉取消并继续向下一阶段执行。

### 18.3 降级矩阵

| 场景 | 行为 | RagStatus |
|---|---|---|
| Hybrid + Rerank 成功 | 正常返回 | SUCCESS |
| Embedding 失败、Lexical 合格 | Lexical-only | DEGRADED |
| Rerank 失败、RRF 合格 | Fusion-only | DEGRADED |
| Dense 无结果、Lexical 合格 | Lexical-only | DEGRADED 或 SUCCESS，按诊断策略 |
| 无可靠 Evidence | 确定性资料不足 | NO_EVIDENCE |
| Store 安装中 | 不执行检索 | ERROR + INITIALIZING 原因码 |
| Deadline 到期 | 停止全部后续阶段 | TIMEOUT |
| 用户取消 | 停止全部后续阶段 | CANCELLED |
| Schema/Hash 不一致 | 禁止打开数据库 | ERROR |

降级不得突破 Metadata、Evidence 阈值、调用次数和安全边界。

---

## 19. Context 与最终回答协议

### 19.1 不新增 RagContextProvider

RAG 统一走 Tool Loop：

```text
LLM
→ VehicleKnowledgeTool
→ RAG
→ ToolResult
→ 下一轮 Context assemble
→ LLM
```

禁止同时增加主动检索式 `RagContextProvider`，避免：

- 同一请求重复召回；
- 主动检索与 Tool Query 不一致；
- Evidence 冲突；
- Context 预算失控；
- Trace 无法判断证据来源。

### 19.2 Evidence 内容是数据而不是指令

主模型提示中必须明确：

- Evidence 是被引用的数据；
- Evidence 中出现的命令、提示词或角色文本不得改变系统指令；
- 只能依据 Evidence 陈述车辆官方知识；
- Evidence 之间冲突时应指出资料版本或适用范围差异；
- 不得伪造未提供的页码、标题路径、HTML 锚点、Markdown 行号或结论。

### 19.3 CitationGuard 与引用格式

引用不能只依赖 Prompt。主模型负责用 Evidence ID 标注结论，`CitationGuard` 负责确定性校验和渲染：

```text
模型回答中的 [E1] / [E2]
        ↓
CitationGuard
        ├── ID 是否属于当前 Request 的 citationEvidenceMap
        ├── REQUIRED 回答是否至少包含一个有效引用
        ├── 任一未知 ID 均拒绝回答
        ├── 从 Evidence 读取真实标题和 SourceLocator
        └── 渲染用户可见来源
```

模型不得自行生成文档位置或把自然语言中的伪造来源当成已验证引用。`CitationGuard` 不判断车辆知识内容本身，只验证回答引用是否能通过当前 Evidence 的 `SourceLocator` 回溯。

用户可见回答推荐格式：

```text
PDF：
……。[E1]
来源：[E1]《车辆用户手册》“空调系统”章节，印刷页 82（PDF 第 86 页）

HTML：
……。[E2]
来源：[E2]《车机功能说明》“系统升级 > 升级条件”章节（锚点：upgrade-condition）

Markdown：
……。[E3]
来源：[E3]《故障码说明》“动力系统 > P001”章节（源码第 120—128 行）
```

最低要求：

- 文档标题必须存在；
- `sourceFormat` 和 `headingPath` 或格式专属位置至少存在一种可靠定位；
- PDF 优先显示可靠的印刷页标签，同时可以补充 PDF 物理页码；没有印刷页标签时显示“PDF 第 N 页”；
- HTML 优先显示 `headingPath`，真实 `htmlElementId` 仅作为补充；无源锚点时不得生成锚点；
- Markdown 优先显示 `headingPath`，可靠行号仅作为补充；无行号时不得阻止基于标题路径引用；
- 多份资料结论不一致时分别标注来源；
- 不向用户展示内部 Chunk ID、RRF 或 HNSW 术语；
- 降级检索不影响引用真实性。

若 REQUIRED 回答包含知识结论却没有有效 Evidence ID，或出现任一不属于当前 Request `citationEvidenceMap` 的 Evidence ID，`CitationGuard` 必须阻止该回答作为成功结果返回，并由 Agent/Runtime 映射为受控的 `KNOWLEDGE_CITATION_INVALID`；该原因码不属于 RAG 检索状态。V1 不通过再次调用模型修复引用，避免额外不可控延迟；是否增加一次受 Deadline 约束的修复调用留待后续评测决定。

### 19.4 Evidence 预算

Evidence 正文使用 Token 预算，并纳入现有 Context 总预算控制：

- 只按 Parent 排名选择完整 Parent，通常返回 2～3 个，最多 4 个；
- 同一 Parent 只出现一次；
- 加入下一个完整 Parent 超出总预算时停止，禁止截断 Parent 或退回到裸 Child；
- 不允许通过截断破坏 JSON 结构；
- Context 不应再次发起 RAG；
- ToolResult 被裁剪时必须保留来源和核心结论。
- RAG 侧使用与 Context 一致的 Token 估算口径；如果无法直接复用实现，必须共享同一版本化算法和配置；
- ToolResult 仍应设置独立的字符或字节硬上限，以防异常序列化和 Binder/内存风险，但该上限不能作为语义预算；
- Rerank 候选预算与最终 Evidence 预算分别计算，不能因为 Rerank 可接收更多文本就扩大主模型上下文。

Rerank 初始语义预算为 6000 Tokens（硬保护 7000）；Parent Evidence 初始总预算为 5000 Tokens。两者都必须使用与分块一致的版本化 TokenEstimator。任何字符/字节硬保护触发都必须记录为传输保护，不能伪装为语义预算裁剪。

### 19.5 KnowledgeMemoryPolicy

RAG 不直接写 Memory，但现有 AgentLoop 会持久化 Tool 交换并在成功回答后触发长期记忆提取，因此知识请求必须增加显式的 `KnowledgeMemoryPolicy`：

- 当前 Agent 请求内可以使用完整模型侧 `VehicleKnowledgeToolResult`；
- 请求级 `KnowledgeTurnBuffer` 保存本请求产生的全部完整 ToolResult，只供本请求后续模型迭代使用，请求结束后必须释放；
- 写入持久化 SessionMemory 的 ToolResult 必须先转为紧凑形式，只保留状态、有效 Evidence ID、必要的短摘要和来源元数据；
- 紧凑化后仍必须保留合法的 `AiMessage(toolCalls) → ToolExecutionResultMessage` 配对，禁止破坏会话消息序列；
- 后续请求主要复用最终回答及已验证引用，不重复携带 Dense/BM25/RRF/Rerank 诊断或大段原始 Evidence；
- SessionMemory 中的历史引用只能作为会话文本，不能加入新请求的 `citationEvidenceMap`，也不能替代新 REQUIRED 请求的检索；
- REQUIRED 知识回答默认跳过现有 `MemoryExtractor` 的长期记忆提取，禁止把车辆手册事实保存为用户偏好；
- 如果未来需要从知识对话提取“用户偏好”，必须使用独立分类和白名单字段，不得重新放开整段知识回答提取；
- 会话压缩、取消、超时或异常终止后仍必须保持 Tool 消息序列可恢复。

该策略属于 AgentLoop/Memory 集成边界，不改变 RAG Store 的只读语义。

`KnowledgeTurnBuffer` 是 Tool 执行结果的请求级临时载体，不是 `RagContextProvider`，不得在后续请求中主动检索或复用旧 Evidence。Context 装配当前迭代时，应按 `toolCallId` 使用 Buffer 中的完整结果替换 SessionMemory 中对应的紧凑投影，禁止追加成重复 Tool 消息；SessionMemory 始终只接收紧凑投影。

---

## 20. Trace 与隐私协议

### 20.1 Span 结构

```text
tool.searchVehicleKnowledge
└── rag.retrieve
    ├── rag.policy_check
    ├── rag.profile_resolve
    ├── rag.lexical_search
    ├── rag.query_embedding
    ├── rag.vector_search
    ├── rag.metadata_filter
    ├── rag.rank_fusion
    ├── rag.rerank
    ├── rag.parent_resolve
    └── rag.evidence_select

agent.knowledge_response
├── rag.tool_result_map
├── rag.citation_validate
└── rag.memory_policy
```

### 20.2 必须记录的诊断信息

- Request ID；
- KnowledgeRequirement；
- 调用序号；
- Query Hash；
- VehicleProfile 是否完整，不记录车辆唯一标识；
- Bundle/Schema/Analyzer 版本；
- Knowledge Scope 解析结果及是否匹配，不记录车辆唯一标识；
- 各阶段候选数量；
- 过滤前后数量；
- 内部 `retrievalEvidenceId`、模型侧 `evidenceId` 和 Document ID；
- Evidence SourceFormat 与 SourceLocator 完整性，不在 Release Trace 记录 HTML/Markdown 原始正文；
- Dense Distance、各阶段 Rank、Lexical/Fusion/Rerank 分数；
- RRF Exact/Near Duplicate 删除数量和原因、Child→Parent 映射结果、Parent 聚合前后数量、最佳 Child 与 Parent 的排名来源；
- Parent Evidence Token 用量、是否因预算停止、`retrievalConfidence` 的原始特征和最终等级；
- Retrieval Mode；
- 降级原因；
- 各阶段耗时；
- 最终状态和失败原因；
- Deadline 剩余量和取消状态。
- ToolResult 映射前后 Token/字符大小；
- CitationGuard 的有效、未知和缺失引用数量；
- KnowledgeMemoryPolicy 的紧凑化与长期记忆抑制结果。

### 20.3 Debug 与 Release

Debug：

- 可以记录经过脱敏和长度限制的 Query/Evidence 摘要；
- 不得记录 DashScope API Key；
- 不得默认记录整份文档。

Release：

- 只记录 Query Hash、Evidence ID、Document ID、分数、数量和耗时；
- 禁止记录完整 Query；
- 禁止记录完整 Evidence；
- 禁止记录 VIN 等车辆唯一标识；
- Trace Export 失败不得影响 RAG 主流程。

---

## 21. 安全与可信边界

### 21.1 数据来源

V1 只接收已审核的官方车辆资料。PDF、静态 HTML、Markdown 均必须作为本地受控文件交付；禁止 CLI 自动抓取 Web 内容、访问远程 URL 或递归读取链接后直接进入正式数据库。

每份源文件必须具有：

- 人工配置的 Metadata；
- SHA-256；
- 文档版本；
- SourceFormat、原始文件名和可信字符集配置；
- 适用车型和地区；
- 构建质量记录。

HTML/Markdown 文件中存在的 URL 只视为文档文本属性，不构成已批准的数据来源，也不授权 CLI 读取对应资源。

### 21.2 Prompt Injection 防护

即使资料来源可信，文档正文仍按“数据”处理：

- 不执行 Evidence 中的指令；
- 不允许 Evidence 改写 System Prompt；
- 不允许文档内容申请额外 Tool；
- ToolResult 使用明确 JSON 边界；
- 主模型 Prompt 要求忽略资料中的角色指令和提示词样式文本。

### 21.3 Tool 参数边界

- Query 是唯一模型可控字段；
- Query 最大 512 字符；
- Query 不得携带可执行表达式；
- Top-K、车型、版本、过滤策略由系统控制；
- 全局 ToolRegistry Dispatch 前必须做本轮授权校验；
- RAG 不绕过 ToolSafetyEngine 执行车控；
- RAG Tool 不接受任意文件路径、URL 或数据库查询表达式。

### 21.4 依赖与许可证门禁

ObjectBox、PDFBox、Tabula、HTML Parser、Markdown Parser 及其传递依赖在正式引入前必须完成版本、许可证和安全审查：

- Android 与 JVM CLI 使用的 ObjectBox 版本必须明确锁定并验证数据库兼容性；
- 必须确认 ObjectBox Runtime、Gradle Plugin 和 Vector Search 的许可证符合项目发布方式；
- PDFBox、Tabula、HTML/Markdown Parser 及传递依赖必须记录许可证和版本；
- 依赖来源必须是受信仓库，禁止把来源不明的 Fat JAR 直接提交为正式依赖；
- 发现许可证或商业发布限制时必须暂停依赖接入并由项目负责人确认，不得在实施计划中默认忽略；
- 依赖升级必须重新执行跨端数据库兼容、PDF/HTML/Markdown 解析回归和安全扫描。

该门禁分为法律/发布确认与技术验证两部分，二者均通过后才能开始正式功能实现：

1. 项目负责人确认 ObjectBox Java Runtime、Gradle Plugin、原生库和 Vector Search 的实际许可证与目标发布方式兼容；
2. 锁定候选 ObjectBox、PDFBox、Tabula、HTML Parser、Markdown Parser 版本及其传递依赖，形成版本与许可证清单；
3. 使用当前 Android AGP 8.9.1、Java 17、目标 ABI 和独立 JVM CLI 完成最小构建；
4. CLI 使用共享 Schema 生成最小 `data.mdb`，Android 使用当前 `MyObjectBox` 模型成功打开；
5. 实测预构建 Store 的 Dense Query、Lexical Query、Scope/Metadata 过滤和错误 Schema 拒绝；
6. 验证 ObjectBox 插件对共享源码/Meta Model 同步方案的行为；
7. 在目标车机或等效 ABI 环境验证原生库加载与 APK/App Bundle 打包结果；
8. 所有 Parser 在同一 CLI 进程中的依赖版本不得冲突，并能分别解析 PDF 文本/表格、静态 HTML DOM/表格、CommonMark/GFM Table 最小样本；
9. 使用带脚本、外部链接、iframe、远程图片和路径逃逸样本验证 HTML Parser 不执行脚本、不访问网络且不读取语料根目录外文件；
10. 使用 Raw HTML、图片链接、Front Matter 和未支持扩展验证 Markdown Parser 遵守同一安全边界并输出诊断。

任一项不通过时，应暂停 ObjectBox 路线并回到总体设计选择替代本地存储或全云端方案，不得在两份实施计划中把验证工作隐藏为普通开发任务。

### 21.5 云端数据边界

RAG 使用 DashScope 时必须遵守：

- Query Embedding 只发送规范化后的用户知识 Query；
- Rerank 只发送候选文档的必要短文本和标题路径；
- 禁止发送完整 `VehicleProfile`、VIN、设备标识和动态车辆状态；
- Region、Workspace、Base URL、Embedding/Rerank 模型、连接/读取超时、重试和最大输入预算由 `RagCloudConfig` 集中配置；
- 重试必须服从同一绝对 Deadline，并避免对确定性参数错误重试；
- Trace 记录调用类型、状态、Token/文本长度和耗时，Release 不记录完整 Query 或候选正文；
- API Key 继续遵守现有安全配置，不进入 APK 文档、Manifest、Trace 或离线构建产物。

---

## 22. 版本与兼容策略

### 22.1 版本维度

系统至少管理：

- `formatVersion`：Manifest/交付格式；
- `bundleVersion`：知识内容构建版本；
- `knowledgeScopeId`：一份 Bundle 对应的车辆知识范围；
- `schemaFingerprint`：ObjectBox Meta Model；
- `objectBoxVersion`：数据库引擎版本；
- `embeddingTemplateVersion`；
- `lexicalAnalyzerVersion`；
- `chunkingConfigVersion`；
- `tokenEstimatorVersion`；
- `hnswConfigFingerprint`；
- `sourceLocatorSchemaVersion`；
- `parserConfigHash` 及各格式 Parser 版本；
- Android 内部 `RagResult` 与模型侧 `VehicleKnowledgeToolResult` 的独立 `schemaVersion`。

### 22.2 必须重建数据库的变化

- 文档新增、删除或内容变化；
- Document Metadata 变化；
- SourceFormat、Parser 实现、解析规则、字符集策略或正文清理规则变化；
- `SourceLocator` 字段或定位语义变化；
- Chunk 规则变化；
- TokenEstimator、Chunking Config、Embedding/BM25 文本渲染或 Parent/Child 映射语义变化；
- Embedding 模型、维度或模板变化；
- ObjectBox Entity、Property、UID 或 HNSW 配置变化；
- Lexical Analyzer 或 postings 格式变化；
- Table 标准化规则影响正文；
- HTML/Markdown 支持语法或安全过滤规则变化；
- 过滤字段语义变化。

### 22.3 APK 更新

V1 更新流程：

```text
发布新 APK
→ 新 Asset Manifest/DB
→ Service 检测 bundleVersion 变化
→ staging 复制和校验
→ 新库试打开
→ 原子切换
→ 成功后清理旧库
```

新库失败时必须保留上一个可用版本。不得先删除旧库再尝试安装新库。

---

## 23. 测试与评测总规范

### 23.1 离线构建测试

至少覆盖：

- 普通中文 PDF；
- 中英混合 PDF；
- PDF 多栏文本、标题、列表和 Warning；
- Lattice 表格；
- Stream 表格；
- 跨页表格；
- 空白页；
- 混合扫描页默认拒绝；
- `corpus.json` 已审核排除页允许发布，以及未审核排除页拒绝；
- PDF 物理页码与印刷页标签映射；
- 纯扫描 PDF 拒绝；
- 需要密码或禁止内容提取的加密 PDF、以及损坏 PDF 拒绝；无密码且允许只读内容提取的 PDF 可解析；
- UTF-8 和显式非 UTF-8 静态 HTML；
- HTML `<main>` / `<article>` / 配置内容根提取；
- HTML 标题、段落、嵌套列表、定义列表、引用、代码块和 DOM 表格；
- HTML 导航、页眉页脚、侧栏和危险节点清理；
- HTML 脚本永不执行、远程资源永不加载、链接永不抓取；
- 脚本移除后无静态正文返回 `DYNAMIC_HTML_UNSUPPORTED`；
- HTML 合并单元格、嵌套表格、超大 DOM 和错误字符集；
- CommonMark 标题、列表、引用、代码块和 GFM Table；
- Markdown Raw HTML 复用静态 HTML 清理；
- Markdown Front Matter 不覆盖 `corpus.json`；
- Markdown 未支持扩展产生诊断；
- HTML/Markdown 图片只保留受控替代文本，不读取图片；
- 输入路径逃逸、符号链接逃逸和文件类型声明不一致拒绝；
- 三种 SourceFormat 归一化后的 StructuredBlock 与 TableBlock 一致性；
- PDF、HTML、Markdown SourceLocator 构建稳定性；
- Embedding 批次顺序和失败；
- Vector 维度、NaN/Infinity；
- Chunk ID 构建稳定性；
- postings 一致性；
- ObjectBox Schema 和统计一致性；
- 共享 Schema 源与物化副本 Hash 一致性；
- 单 `knowledgeScopeId` 门禁和跨 Scope 文档拒绝；
- HNSW 配置指纹；
- Manifest Hash。

### 23.2 Android 单元与集成测试

至少覆盖：

- Asset DB 安装、版本相同跳过、版本升级；
- 空间不足拒绝、staging 残留恢复和进程终止恢复；
- Hash/Schema 不一致拒绝；
- staging 失败回滚；
- VehicleProfile 精确、通用和缺失匹配；
- Knowledge Scope 精确匹配、无法解析和版本不匹配；
- Dense、Lexical、RRF、Rerank；
- Embedding/Rerank 降级；
- Evidence 去重和预算；
- Rerank 候选 Token 预算与模型侧 Evidence Token 预算隔离；
- 调用次数和 Query 去重；
- 单迭代多个 RAG Tool Call 拒绝，第二次查询只在下一迭代发生；
- 二次检索的 Evidence ID 在同一 Request 内继续编号且无冲突；
- V1 Detector 不输出 OPTIONAL，REQUIRED 只开放 RAG Tool；
- Deadline 和取消；
- REQUIRED 未调用 Tool；
- REQUIRED 无 Evidence；
- Tool 非本轮授权拒绝；
- ToolResult JSON Schema；
- 内部 RagResult 诊断字段不会泄露到模型 ToolResult；
- CitationGuard 接受有效 ID、拒绝未知/缺失 ID，并按 PDF、HTML、Markdown 正确渲染 SourceLocator；
- KnowledgeMemoryPolicy 紧凑化后消息序列合法，且知识回答不进入长期记忆提取；
- 历史 Session 引用不能加入新 Request 的 Citation 映射或替代 REQUIRED 检索；
- Release Trace 不泄露正文；
- 云端 Embedding/Rerank 请求不包含 VehicleProfile、VIN 或动态车辆状态；
- 数据库只读业务语义。

### 23.3 跨端兼容门禁

必须存在自动化或可重复执行的兼容验证：

1. JVM CLI 生成 `data.mdb`；
2. 使用 Android 侧同一 Schema 模型打开；
3. 校验 Manifest 与 Store Metadata；
4. 校验 `knowledgeScopeId` 和 HNSW 配置指纹；
5. 执行至少一次 Dense Query；
6. 执行至少一次 Lexical Query；
7. 执行一次 Scope 与 Metadata 精确过滤；
8. 分别验证 PDF、静态 HTML、Markdown Chunk、Document 和 SourceLocator；
9. 验证错误 Schema、错误 Scope 和错误 HNSW 配置无法激活；
10. 在目标 ABI 验证原生库加载和最小查询。

没有通过跨端门禁的数据库禁止进入 APK Assets。

### 23.4 RAG 评测

真实文档到位后必须建立人工评测集，至少包含：

- 功能使用方法；
- 使用条件和限制；
- 故障码与缩写；
- 口语化 Query；
- 车型/版本过滤；
- 无答案问题；
- 多来源冲突；
- 表格答案；
- PDF、HTML、Markdown 相同知识的跨格式召回一致性；
- HTML/Markdown 标题路径引用；
- 离线降级；
- 二次检索。
- 无有效引用和伪造 Evidence ID；
- Scope/软件版本不匹配；

Eval V2 的主口径是“最终 Top-K Parent Evidence 是否完整覆盖至少一个人工确认的可接受证据集合”，不再以单个 Child ID 命中决定 RAG 成败：

- 一个 Case 的 `acceptableEvidenceSets` 之间为 OR；同一集合中的 `requiredParentIds` 为 AND；`optionalLocatorChildIds` 仅用于 Child 定位诊断；
- 主指标至少包括 `ParentEvidenceCoverage@1/@2/@3/@4`、`ParentEvidenceMRR` 与 `NoEvidenceAccuracy`；Dense/BM25/RRF/Rerank Child Recall 仅作故障定位；
- 首版必须人工复核不少于 50 条真实问题，覆盖单 Parent、多 Parent、NO_EVIDENCE、近重复干扰、PDF/HTML/Markdown 与主要车辆知识类别；DEV 仅用于调参与置信度校准，TEST 不得参与反复调参；
- RRF、Exact Dedup、Near Dedup、Rerank 与 RRF fallback 必须在同一 Bundle、同一 DEV 集做单变量消融；
- `HIGH/MEDIUM/LOW` 阈值必须从 DEV 的 Parent Evidence 正确性、Score/Margin/降级分布校准，再在 TEST 集仅运行一次确认；校准前只输出 `UNASSESSED`；
- 可提交报告只保存 caseId、Query Hash、ID、排名、分数、计数、置信度和原因码；Query、正文和人工备注只保留在本地忽略的审核文件。
- 大 Evidence 对 Context 和后续会话的影响。

指标至少包括：

- Dense/Lexical/Hybrid Recall@K；
- MRR 或 nDCG；
- Metadata Filter 正确率；
- No-Evidence 判断准确率；
- Citation 正确率；
- 最终回答 Faithfulness；
- 本地检索 p50/p95；
- Embedding/Rerank/完整请求 p50/p95；
- 超时、取消和降级成功率。

正式阈值由真实文档和评测数据确定，不在总体设计阶段伪造数值。

---

## 24. 两份实施计划的共同门禁

两份实施计划可以在门禁执行前编写，但必须把第 21.4 节的许可证确认与最小技术验证列为共同 Phase 0。Phase 0 未通过前，后续功能阶段不得标记为可开始，也不得提交正式依赖接入。

### 24.1 离线端计划必须回答

- CLI 工程和命令行接口；
- PDFBox、Tabula、HTML/Markdown Parser、ObjectBox 依赖及兼容性；
- `corpus.json` Schema，包括 SourceFormat、字符集、HTML 内容根/排除 Selector 和 PDF 审核排除页；
- DocumentParserRegistry，以及 PDF、静态 HTML、Markdown Parser 的边界；
- StructuredBlock、Chunk、Table、SourceLocator 的代码模型；
- Chunk 配置项；
- Embedding Client、批处理、重试和失败处理；
- Analyzer 和 postings 构建；
- ObjectBox Schema 的唯一来源；
- ObjectBox/PDFBox/Tabula/HTML Parser/Markdown Parser 前置版本、许可证和兼容性验证；
- 单 Scope Bundle、`knowledgeScopeId` 生成与 `corpus.json` 审核规则；
- Manifest 和 Report 生成；
- PDF、静态 HTML、Markdown 测试语料、安全样本和质量门禁；
- 如何生成可被 Android 打开的 `data.mdb`。

### 24.2 Android 端计划必须回答

- ObjectBox 依赖和 ABI；
- Store 安装、回滚、生命周期和线程模型；
- 安装空间计算、流式复制、落盘和进程终止恢复；
- VehicleProfileProvider 与 VehicleStateMachine 扩展；
- Query Embedding 和 Rerank Client；
- Dense/Lexical/RRF/Evidence 组件；
- KnowledgeNeedDetector；
- KnowledgeScopeResolver 与 Scope 不匹配行为；
- ToolGroup 和 Tool 注册；
- 60 秒 Deadline 接入；
- RequestCallRegistry 取消；
- REQUIRED 强制查证；
- V1 OPTIONAL 保留但不输出、单迭代单 RAG Call、请求级 Evidence 编号和 `KnowledgeRequestState`；
- Tool 本轮授权复核；
- 内部 RagResult 到模型侧 ToolResult 的隔离映射；
- Token Evidence 预算、CitationGuard、KnowledgeTurnBuffer 和 KnowledgeMemoryPolicy；
- PDF、HTML、Markdown SourceLocator 的 Android 映射与用户可见引用渲染；
- Trace、隐私和错误映射；
- 单元、集成和 AgentLoop 测试。

### 24.3 联合完成定义

只有同时满足以下条件，RAG 才算形成完整闭环：

- CLI 能从 PDF、静态 HTML、Markdown 测试语料生成同一份合格预构建数据库；
- 依赖许可证、版本和最小跨端技术验证已经通过；
- Android 能安全安装并打开该数据库；
- Hybrid Retrieval 能返回可信 Evidence；
- Active Bundle 与车辆 `knowledgeScopeId` 严格匹配；
- 明确知识请求必须执行 RAG；
- 每个 Agent 迭代至多执行一个 RAG Tool Call，第二次检索只在下一迭代发生；
- 无 Evidence 时不会生成无依据知识结论；
- 最终回答具有真实文档引用；
- PDF、HTML、Markdown 引用均能回溯到正确 SourceLocator；
- CitationGuard 不允许未知或缺失 Evidence ID，KnowledgeMemoryPolicy 不把官方知识写入长期记忆；
- 取消、超时、离线和 Rerank 失败能够受控处理；
- 跨端 Schema、版本和 Manifest 门禁通过；
- 项目单元测试、构建和 Lint 通过；
- RAG 评测报告能够解释当前质量和边界。

---

## 25. 后续演进方向

V1 稳定后可以按独立设计推进：

1. PDF OCR 和图片表格解析；
2. 知识 OTA、增量下载和签名验证；
3. 更细粒度的软件版本范围协议；
4. 统一 `Domain + Task + RequiredCapabilities` Router；
5. Vehicle State + Knowledge 联合解释；
6. Knowledge + Control 的显式分阶段执行；
7. 多模态车辆手册；
8. 更完善的 Citation/Faithfulness 自动评测；
9. 基于真实数据调整 Chunk、HNSW、BM25 和 Rerank 策略。

上述演进不得破坏 V1 已建立的可信 Metadata、Evidence 边界、Tool 授权、Deadline 和版本协议。

动态 HTML、浏览器渲染和网页抓取不属于本设计的自然扩展项。未来若业务确实需要，必须作为独立系统重新评估网络安全、内容授权、渲染沙箱和构建可复现性，不得在当前离线 CLI 中逐步放开。

---

## 26. 参考资料

- [ObjectBox On-Device Vector Search](https://docs.objectbox.io/on-device-vector-search)
- [ObjectBox 预构建数据库说明](https://docs.objectbox.io/faq)
- [ObjectBox Meta Model、ID 与 UID](https://docs.objectbox.io/advanced/meta-model-ids-and-uids)
- [ObjectBox Property Types](https://docs.objectbox.io/property-types)
- [ObjectBox Licensing FAQ](https://objectbox.io/faq/)
- [ObjectBox Android App Bundle 与 Split APK](https://docs.objectbox.io/android/app-bundle-and-split-apk)
- [Apache PDFBox](https://pdfbox.apache.org/)
- [Apache PDFBox Text Extraction FAQ](https://pdfbox.apache.org/3.0/faq.html)
- [Tabula Java](https://github.com/tabulapdf/tabula-java)
- [CommonMark Specification](https://spec.commonmark.org/)
- [GitHub Flavored Markdown Specification](https://github.github.com/gfm/)
- [WHATWG HTML Parsing](https://html.spec.whatwg.org/multipage/parsing.html)
- [DashScope Text Embedding API](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api)
- [DashScope Text Rerank API](https://help.aliyun.com/en/model-studio/text-rerank-api)

---

## 27. 结论

本方案将 RAG 定位为现有主 Agent 的可信车辆知识检索能力：

- 离线 JVM CLI 负责把官方 PDF、本地静态 HTML 和 Markdown 统一转换为带结构、SourceLocator、向量、词法索引和版本信息的预构建 ObjectBox 数据库；
- HTML 端严格限定为静态 DOM 解析，不执行脚本、不启动浏览器内核、不访问网络、不抓取链接或加载外部资源；
- Markdown 固定遵守 CommonMark + GFM Table，三种格式从 StructuredBlock 开始完全复用后续构建链；
- Android 端只负责安装、查询、融合、Rerank、Evidence 输出和 Agent 接入；
- 主 Agent 保持唯一推理与回答主体；
- 一份 Bundle 只服务一个 `knowledgeScopeId`，VehicleProfile、Scope、Metadata、Tool 授权、Deadline 和 Evidence 阈值构成确定性控制边界；
- 明确知识问题必须查证，无可靠证据时必须承认资料不足；
- REQUIRED 请求只开放 RAG Tool，每迭代至多一次检索，第二次检索必须在读取第一次结果后进行；
- 内部检索诊断不进入模型上下文，引用由 `CitationGuard` 闭环，知识会话由 `KnowledgeMemoryPolicy` 防止上下文膨胀和长期记忆污染；
- 两份后续实施计划必须共同遵守本文件中的 Schema、Scope、Manifest、Embedding、Lexical、ToolResult、Memory、Citation、Trace 和兼容协议。

后续可以分别编写离线构建端和 Android Agent 端实施计划，但两份计划必须以依赖许可证确认与最小跨端兼容性验证作为共同 Phase 0；门禁通过后才能进入正式功能实现阶段。
