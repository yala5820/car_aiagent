# AIAgent RAG — 车辆知识库离线构建与交付

## 1. 项目概述

AIAgent RAG 是面向车载智能 Agent 的车辆知识库系统。它由两个边界明确的部分组成：

- `tools/rag-indexer/`：独立 Java 17 桌面 JVM CLI，负责文档解析、结构恢复、Parent-Child 分块、向量化、BM25 索引、ObjectBox 写入、质量验证、评测和 Bundle 发布。
- Android AIAgent：只读消费已经构建完成的 Bundle，通过受控 Tool Calling 提供知识检索，不在车机上解析原始 PDF、HTML 或 Markdown，也不在运行时重建文档向量。

离线端与 Android 端通过 `rag-schema/` 中的共享 Entity、ObjectBox Meta Model、Manifest Schema 和 Fixture 保持协议一致。离线端是知识数据的生产者，Android Agent 是只读消费者。

**核心目标：**

- 将获准的车辆手册、静态网页和 Markdown 资料转化为可审计、可复现的知识 Bundle。
- 使用 Child 提高 Dense/BM25/Rerank 的定位精度，使用完整 Parent 恢复回答所需上下文。
- 在 APK 中内置只读知识库，让 Agent 通过 `searchVehicleKnowledge` 主动检索依据。
- 将知识检索限制在确定的车型 Scope、请求期限、候选数量和 Evidence 预算内。

**当前 Demo Scope：**

| 字段 | 当前值 |
|------|--------|
| 车型 | Tesla Model Y |
| 年款 | 2026 |
| 区域 | 中国大陆（CN） |
| 版本 | 2026 焕新版 |
| 配置 | 后驱版（RWD） |
| `knowledgeScopeId` | `model-y-2026-cn-2026-refresh-rwd` |
| Bundle 状态 | `TEST_ONLY`，用于 Demo 跑通和检索调试 |

> `TEST_ONLY` 表示资料覆盖、评测样本和发布审批尚未达到正式产品标准，但不影响当前 Demo APK 完成知识库安装、检索和 Agent Tool 调用。

### 改造历史

| 阶段 | 说明 |
|------|------|
| **Phase 1** | 建立独立 Java 17 CLI、稳定退出码、输入安全边界和共享 Schema |
| **Phase 2** | 支持文本型 PDF、严格静态 HTML、Markdown 和表格结构解析 |
| **Phase 3** | 建立标题层级、来源定位、Parent-Child 分块和稳定 ID |
| **Phase 4** | 接入 DashScope `text-embedding-v4`、Embedding Cache 和批处理重试 |
| **Phase 5** | 建立 CJK/Latin BM25 倒排索引、ObjectBox HNSW 向量索引和三文件 Bundle |
| **Phase 6** | 建立 Validate、Verify、人工 Parser/Chunk Review 和离线 Eval |
| **Phase 7** | 优化为小节级 Parent、段落重建、语义 Child 合并和 Parent Evidence |
| **Phase 8** | 对齐 Android Dense + BM25 + RRF + 去重 + Child Rerank + Parent 聚合链路 |
| **Phase 9** | 将 `TEST_ONLY` Bundle 内置到 APK，通过只读知识 Tool 接入 AgentLoop |

---

## 2. 项目架构与技术栈

### 架构分层

```text
获准的 PDF / 静态 HTML / Markdown
                │
                ▼
┌─────────────────────────────────────────────────────────┐
│ tools/rag-indexer：离线知识生产端                         │
│                                                         │
│ Corpus/Config 校验                                      │
│   → 文档解析与安全清洗                                   │
│   → 标题、版面、表格、来源定位                           │
│   → 小节级 Parent                                       │
│   → Paragraph Reconstruction                            │
│   → 检索级 Child                                        │
│   → Child Embedding + TITLE/BODY BM25                   │
│   → ObjectBox Store + Manifest + Build Report           │
│   → Verify / Evaluate / 人工审核                         │
└──────────────────────────┬──────────────────────────────┘
                           │ 三文件 Bundle
                           ▼
             data.mdb + manifest.json + build-report.json
                           │
                           │ APK 仅内置运行所需文件
                           ▼
┌─────────────────────────────────────────────────────────┐
│ Android AIAgent：只读消费端                              │
│                                                         │
│ Asset 校验/安装/激活                                     │
│   → Query 标准化与车型 Scope                             │
│   → Child Dense + BM25                                   │
│   → RRF → 候选去重 → Child Rerank                       │
│   → Child 映射 Parent → Parent 去重与预算                │
│   → 完整 Parent Evidence                                 │
│   → searchVehicleKnowledge Tool                          │
│   → 主模型基于 Evidence 组织最终答复                     │
└─────────────────────────────────────────────────────────┘
```

### 技术栈

| 类别 | 技术 |
|------|------|
| 离线运行环境 | Java 17、独立 Gradle Wrapper |
| PDF | Apache PDFBox 2.0.37；Tabula 1.0.5 用于表格提取 |
| HTML | Jsoup 1.22.2；仅静态 DOM，不联网、不执行 JavaScript |
| Markdown | CommonMark 0.28.0 + GFM Table |
| 配置与协议 | Jackson 2.20.1、JSON Schema、共享 `rag-schema/` |
| CLI | Picocli 4.7.7 依赖 + 项目受控命令层 |
| 向量模型 | DashScope `text-embedding-v4`，1024 维 |
| Rerank | DashScope Rerank API，Android 运行时调用 |
| 向量与存储 | ObjectBox 5.4.0、HNSW、COSINE |
| 关键词检索 | 自研中英文 Analyzer + BM25 |
| 网络 | OkHttp；仅 Embedding、Rerank 等明确允许的云端阶段使用 |

---

## 3. 项目目录结构

```text
AIAgent/
├── tools/
│   ├── README.md                         # 本文档：RAG 总体构建与交付说明
│   └── rag-indexer/
│       ├── build.gradle.kts              # 独立 Java 17 CLI Build
│       ├── settings.gradle.kts
│       ├── gradlew / gradlew.bat
│       ├── examples/
│       │   ├── corpus.example.json       # 语料清单示例
│       │   └── rag-build.example.json    # 构建参数示例
│       ├── corpus/
│       │   └── model_y_2026_refresh_trial/
│       │       ├── corpus.json           # 当前试运行语料与车型 Scope
│       │       ├── rag-build-v2-review.json
│       │       ├── evaluation_*.json     # 离线评测集
│       │       ├── documents/            # 原始 PDF/HTML/Markdown
│       │       └── work/                 # 审核页、报告、日志和中间产物
│       ├── trial-output/                 # TEST_ONLY 候选 Bundle
│       ├── scripts/                      # 可复现构建/评测脚本
│       └── src/
│           ├── main/java/.../
│           │   ├── cli/                  # validate/build/verify/evaluate
│           │   ├── corpus/               # Corpus、Scope、安全校验
│           │   ├── parser/               # PDF/HTML/Markdown Parser
│           │   ├── chunk/                # Parent、Paragraph、Child
│           │   ├── embedding/            # 向量请求、缓存、重试
│           │   ├── lexical/              # Analyzer、BM25 索引
│           │   ├── store/                # ObjectBox 写入与验证
│           │   ├── artifact/             # Manifest 与 Bundle 发布
│           │   ├── evaluation/           # RRF/Rerank/Eval
│           │   ├── pipeline/             # 固定构建阶段与断点
│           │   └── report/               # 构建和人工审核报告
│           └── test/
├── rag-schema/
│   ├── src/main/java/                    # 离线端与 Android 共用 Entity/契约
│   ├── objectbox-models/default.json     # 共享 ObjectBox UID 模型
│   ├── contracts/                        # Manifest 等协议资产
│   └── test-fixtures/                    # 跨运行时兼容 Fixture
└── app/src/main/assets/rag/knowledge_db/
    ├── data.mdb                          # APK 内置只读知识数据
    └── manifest.json                     # 安装、Scope、Hash 与协议元数据
```

原始资料、包含正文的人工审核页、向量缓存和工作目录可能含受授权内容或密钥相关运行信息，不应提交到公共仓库。

---

## 4. 核心组件

### 4.1 Corpus 与构建配置

`corpus.json` 描述“构建什么”，包括：

- `bundleId`、`bundleVersion`
- `knowledgeScopeId`
- 车型、年款、区域、软件版本和配置代码
- 文档 ID、标题、文档类型、语言、格式、相对路径
- 原文件 SHA-256
- 单文档适用范围

`rag-build*.json` 描述“怎样构建”，包括：

- PDF、HTML、Markdown Parser 策略
- Parent/Child Token 边界
- 段落恢复和语义合并参数
- Embedding Provider、模型、维度和模板版本
- BM25 Analyzer 版本
- ObjectBox/HNSW 协议
- `TEST_ONLY` 或 `APPROVED` 质量状态

配置、Corpus 和 Parser 参数都会进入指纹。更改文档、分块参数、Embedding 模板、向量模型、Schema 或 HNSW 参数后，必须生成新的 Bundle，不允许在旧数据库上局部拼接。

### 4.2 文档解析与内容清洗

#### PDF

- 使用 PDFBox 读取文本型 PDF 的字符、坐标、字号和页面信息。
- 检测页面单双栏布局；当前双栏标准阅读顺序是“左列从上到下，再右列从上到下”。
- Block 保持接近原始行级结果，主要负责准确保留版面事实，不承担最终语义分块。
- 识别标题、目录引用、页码、重复页眉/页脚、WARNING 和表格候选。
- 允许明确属于同一句或同一段的跨页恢复，但禁止无条件跨页合并。
- Tabula 负责支持范围内的文本型表格；扫描件、OCR 和依赖图像解码的 PDF 不在当前范围。

#### 静态 HTML

- 使用 Jsoup 解析本地静态 DOM。
- 禁止网络访问、脚本执行和动态页面渲染。
- 删除配置指定的导航、面包屑、页头页脚和关联链接等噪声。
- 保留标题层级、段落、列表、警告和表格。
- 支持 Tesla 服务中心页面的受控嵌入数据提取。
- HTML 文件夹输入会先整理为确定性的派生静态输入，保证顺序和 Hash 可复现。

#### Markdown

- 使用 CommonMark 解析。
- 支持 GFM Table。
- 标题、段落、列表、引用和表格进入统一语义 Block 模型。

所有格式最终统一为带 `headingPath`、结构类型和 `SourceLocator` 的解析结果，使后续分块、审核和引用不依赖原始格式。

### 4.3 Parent-Child 分块

#### Parent

Parent 是文档中由标题界定的最小语义完整小节：

- 大章节只作为标题路径 Metadata，不直接作为超大 Parent。
- 理想范围约 `150–1200 tokens`。
- 软上限 `1200 tokens`，超过后优先按独立子主题拆分。
- 硬上限 `2000 tokens`，仍超限时执行语义兜底拆分。
- 一个章节拆成多个 Parent 时允许约 `10%` Parent 内边界重叠。
- Parent 保存一级/二级标题路径、来源定位、适用 Scope 和完整正文。

#### Paragraph Reconstruction

PDF 的行级 Block 会在 Parent 内重建为 Paragraph：

- 使用列、页、坐标、行距、缩进、结构类型和标点关系判断段落连续性。
- 不跨 Parent 恢复。
- 列表、WARNING 和表格作为原子语义组，但超过硬限制时仍允许受控拆分。
- 语义职责主要落在 Paragraph 和 Child 阶段，而不是强迫每个 PDF 行级 Block 自身完整。

#### Child

Child 是 Dense、BM25、RRF 和 Rerank 的检索单元：

- 理想范围 `160–320 tokens`，目标值约 `256 tokens`。
- 软上限 `384 tokens`，硬上限 `512 tokens`。
- 短且完整的 Parent 可以直接形成单一 Child。
- WARNING 等结构边界优先独立成 Child。
- 已处于合理区间的 Paragraph 优先单独成 Child。
- 多个短 Paragraph 可以合并，但不得越过 Parent 或结构原子边界。
- PDF 短段落合并使用 Paragraph Embedding；相邻语义向量 `cosine > 0.7` 才合并。
- 默认 Child overlap 为 `0`；只有单个 Paragraph 超过硬上限、必须按句子或次级标点进行长度兜底时，才允许少量 overlap。

每个 Child 保存稳定 `chunkId`、`parentId`、`chunkIndex`、标题路径、正文、证据类型、Token 数和来源定位。

### 4.4 Embedding 与 Dense 索引

离线端只为 Child 生成检索向量。输入模板为：

```text
二级标题/完整 headingPath
Child 正文
```

当前配置：

- Provider：DashScope
- Model：`text-embedding-v4`
- Dimension：1024
- Distance：COSINE
- HNSW：`neighborsPerNode=30`、`indexingSearchCount=100`

向量阶段具备：

- 稳定请求 ID 和批次规划
- 输入长度校验
- 返回维度与数值合法性校验
- 有界重试
- 文件缓存，避免同一模板和文本重复调用
- 取消令牌和构建 Checkpoint

API Key 只能来自受控环境变量或仓库根目录本机 `local.properties`，不得写入 Corpus、构建配置、Manifest、报告或 Git。

### 4.5 BM25 字段化索引

BM25 与向量检索共用 Child，但标题和正文按独立字段写入：

- `TITLE`：二级标题/标题路径，初始检索权重高于正文。
- `BODY`：Child 正文。
- 中文使用 Bigram/Trigram，英文和数字标识保留精确 Latin Token。
- Analyzer 版本、字段、词频和文档长度写入 ObjectBox，Android 使用同版本 Analyzer 查询。

字段化设计避免把标题简单重复拼接进正文词频，同时让“驾驶员设定”“质保”“服务中心”等标题知识获得更高的精确命中机会。

### 4.6 ObjectBox 与共享 Schema

ObjectBox 同时保存：

- 文档 Metadata
- Parent 和 Child Chunk
- Child 1024 维向量及 HNSW 配置
- TITLE/BODY Lexical Term
- Store Metadata、Schema 指纹和 Scope

`rag-schema/objectbox-models/default.json` 是离线端与 Android 共用的 Meta Model。Entity UID 或 Schema 发生变化时，必须由 Schema 负责人同步更新：

- 共享 Entity
- Meta Model
- Fixture
- Manifest/Schema 指纹
- CLI 与 Android 兼容 Gate

禁止删除 Meta Model 后让 ObjectBox 静默生成新 UID，否则离线数据库可能无法被 Android 打开。

### 4.7 Bundle、验证和发布

完整离线 Bundle 只有三个文件：

```text
candidate/
├── data.mdb
├── manifest.json
└── build-report.json
```

- `data.mdb`：ObjectBox 数据、向量和倒排索引。
- `manifest.json`：Bundle、Scope、Parser、Chunking、Embedding、HNSW、Schema、文件 Hash 和统计信息。
- `build-report.json`：离线构建阶段、质量检查和可发布性报告。

构建顺序固定为：

```text
VALIDATED → PARSED → CHUNKED → EMBEDDED
→ INDEXED → STORE_WRITTEN → VERIFIED
```

发布规则：

- 所有文件先写入 staging。
- Verify 成功后才能移动到输出目录。
- 输出目录必须不存在，禁止覆盖旧候选。
- staging 与 output 必须位于同一 FileStore。
- 使用原子目录移动，防止产生半成品 Bundle。
- `TEST_ONLY` 可以交付 Demo 联调候选，但不能伪装成 `APPROVED`。

APK 运行只需要 `data.mdb` 和 `manifest.json`；`build-report.json` 保留在离线审计侧，不进入 Android Asset。

### 4.8 人工审核与离线评测

`validate` 在 `work/runs/<runId>/` 生成：

- `parser-review.json`：不含正文的结构化诊断。
- `parser-review.html`：Parser 保留内容、页眉页脚排除项和来源位置预览。
- `chunk-review.html`：Parent/Child、稳定 ID、Token、类型和正文预览。

包含正文的 HTML 仅用于本机人工审核，不进入 Bundle。

离线评测支持：

- Dense-only
- BM25-only
- Dense + BM25 + RRF
- Exact/Near Duplicate 去重
- Child Rerank
- Rerank 失败回退
- Parent Evidence Coverage@K、MRR 等指标

Eval V2 允许一个问题对应多个有效 Parent/Child Evidence，评判重点是“证据是否足以回答问题”，而不是机械要求唯一 Chunk。

---

## 5. 初始化流程与运行流程

### 5.1 离线构建流程

```text
1. 准备获准原始资料
2. 编写 corpus.json，锁定车型 Scope、版本和文件 SHA-256
3. 编写 rag-build.json，锁定 Parser/Chunk/Embedding/BM25/HNSW
4. validate：解析与人工审核，不联网、不生成数据库
5. 审核 parser-review.html 和 chunk-review.html
6. build：全量解析、分块、Embedding、BM25、ObjectBox、Manifest
7. verify：独立打开 Store，检查 Hash、Schema、Scope 和统计一致性
8. evaluate-v2 / evaluate-v2-rerank：运行检索评测
9. 根据 Demo 或正式 Gate 决定 TEST_ONLY / APPROVED
10. 将 data.mdb + manifest.json 复制到 Android main assets
11. 构建 APK，Gradle Gate 校验共享 Schema 和 Asset 一致性
```

### 5.2 Android 安装与激活

```text
AIAgentService.onCreate()
    → 创建 KnowledgeStoreManager
    → KnowledgeStoreCoordinator.initializeAsync(vehicleProfile)
    → 后台读取 assets/rag/knowledge_db
    → 校验 Manifest、data.mdb SHA-256、Schema、Scope 和存储空间
    → 复制到 app filesDir 的版本目录
    → ObjectBox 打开候选 Store 并验证 Store Metadata
    → 原子切换 Active Bundle
    → KnowledgeStoreManager 状态变为 READY
```

安装失败不会阻塞 AIAgent Service、普通聊天或车控。若存在上一次已经验证的活动库，则优先恢复旧库；否则知识 Tool 返回稳定的不可用原因。

### 5.3 Android 检索链

```text
Query
  → QueryNormalizer
  → VehicleProfile/knowledgeScopeId 过滤
  → BM25 Child Top 20
  → Query Embedding
  → Dense Child Top 20
  → RRF(k=60)
  → Chunk ID 合并 + 完全重复/高相似/同 Parent 占位去重
  → Child Rerank
  → Top Child 映射 Parent
  → Parent 去重，以最高 Child 分数代表 Parent
  → Evidence 预算，最多返回 4 个完整 Parent
  → VehicleKnowledgeToolResult
```

降级策略：

- Query Embedding 失败但 BM25 有结果：降级为 Lexical-only。
- Rerank 失败：保留 RRF 顺序，不伪造 Rerank 分数或置信度。
- Scope 不匹配、Store 未就绪或 Evidence 不足：返回结构化不可回答结果。
- 请求取消或 deadline 到期：取消对应云端 Call 并结束检索。

### 5.4 向 AIAgent 提供知识能力

离线端不作为网络服务运行，也不接受 Android 请求。它通过 Bundle 向 AIAgent 交付数据。

Android 将 `VehicleKnowledgeTool` 注册进统一 `ToolRegistry`：

```text
LLM Tool Call: searchVehicleKnowledge(query)
    → VehicleKnowledgeService
    → HybridRetrievalCoordinator
    → 完整 Parent Evidence + 来源定位 + 置信度
    → 结构化 ToolResult 写回 AgentLoop
    → 主模型依据 Evidence 生成最终答复
```

Tool 只读，不执行车控、不读取实时车辆状态、不进行导航，也不允许模型传入或篡改车型 Scope。

---

## 6. 当前开发状态

| 模块 | 当前状态 | 说明 |
|------|----------|------|
| PDF/静态 HTML/Markdown Parser | Demo 可用 | 文本型 PDF、静态 DOM、CommonMark 已接通；无 OCR/动态网页 |
| Parent-Child V2 | 已实现 | 小节 Parent、Paragraph Reconstruction、语义 Child、稳定 ID |
| Embedding | 已实现 | DashScope 1024 维、缓存、批处理、重试和校验 |
| BM25 | 已实现 | TITLE/BODY 字段化、中英文 Analyzer |
| ObjectBox Bundle | 已实现 | 三文件离线 Bundle、共享 Schema、Manifest 和 Verify |
| Android Asset 安装 | 已实现 | main APK 内置数据，异步校验、安装、恢复和激活 |
| Hybrid Retrieval | 已实现 | Dense/BM25/RRF/去重/Child Rerank/Parent Evidence |
| Agent Tool 接入 | 已实现 | `searchVehicleKnowledge` 已进入 AgentLoop |
| 正式发布质量 Gate | 未完成 | 当前资料和 Eval 仅满足 Demo 跑通，Bundle 仍为 `TEST_ONLY` |

### 当前已知边界

- 当前资料只覆盖单一 Model Y 2026 中国大陆后驱版 Scope，且内容并非完整产品知识全集。
- PDF 只支持文本型资料；扫描件需要另行设计 OCR 流程。
- HTML 只支持静态输入，不建设动态网页抓取、浏览器渲染或在线同步系统。
- 当前 Bundle 约包含 4 份逻辑文档、951 个 Parent、1878 个 Child，数据以实际 Manifest 为准。
- Android Query Embedding 与 Rerank 需要 DashScope 网络和 API Key；BM25 可作为受控降级路径。
- 当前 Eval 适合 Demo 回归，不代表量产召回率、拒答准确率或安全验收完成。

---

## 7. 本地配置、构建与调试

### 7.1 环境要求

- JDK 17
- Windows PowerShell 或兼容 Shell
- 可访问 DashScope 的网络环境（仅 `build` Embedding 和 Rerank 评测需要）
- 仓库根目录存在共享 `rag-schema/`

### 7.2 API Key

在仓库根目录本机 `local.properties` 配置：

```properties
dashscope.api_key=your_dashscope_key
```

不要将真实 Key 写入：

- `corpus.json`
- `rag-build.json`
- Eval 数据集
- README
- Build Report
- Git 提交

### 7.3 构建 CLI

必须使用 Indexer 自己的 Wrapper：

```powershell
cd tools\rag-indexer
.\gradlew.bat test
.\gradlew.bat installDist
.\build\install\rag-indexer\bin\rag-indexer.bat --help
```

根目录 Android `gradlew.bat` 不包含 `rag-indexer` 子工程。

### 7.4 常用命令

```powershell
# 解析、质量检查与人工审核材料；不调用 Embedding
.\build\install\rag-indexer\bin\rag-indexer.bat validate `
  --corpus .\corpus\model_y_2026_refresh_trial\corpus.json `
  --config .\corpus\model_y_2026_refresh_trial\rag-build-v2-review.json `
  --work-dir .\corpus\model_y_2026_refresh_trial\work

# 全量构建新候选；output 必须不存在
.\build\install\rag-indexer\bin\rag-indexer.bat build `
  --corpus .\corpus\model_y_2026_refresh_trial\corpus.json `
  --config .\corpus\model_y_2026_refresh_trial\rag-build-v2-review.json `
  --output .\trial-output\candidate-new `
  --work-dir .\corpus\model_y_2026_refresh_trial\work

# 独立验证候选
.\build\install\rag-indexer\bin\rag-indexer.bat verify `
  --bundle .\trial-output\candidate-new `
  --config .\corpus\model_y_2026_refresh_trial\rag-build-v2-review.json

# Parent Evidence V2：RRF
.\build\install\rag-indexer\bin\rag-indexer.bat evaluate-v2 `
  --bundle .\trial-output\candidate-new `
  --dataset .\corpus\model_y_2026_refresh_trial\evaluation_parent_evidence_v2.json `
  --report .\corpus\model_y_2026_refresh_trial\work\evaluation-v2.json

# Parent Evidence V2：Rerank
.\build\install\rag-indexer\bin\rag-indexer.bat evaluate-v2-rerank `
  --bundle .\trial-output\candidate-new `
  --dataset .\corpus\model_y_2026_refresh_trial\evaluation_parent_evidence_v2.json `
  --report .\corpus\model_y_2026_refresh_trial\work\evaluation-v2-rerank.json
```

### 7.5 APK 打包检查

将经过确认的候选运行文件复制到：

```text
app/src/main/assets/rag/knowledge_db/data.mdb
app/src/main/assets/rag/knowledge_db/manifest.json
```

然后在仓库根目录执行：

```powershell
.\gradlew.bat :app:verifyRagSharedSchema
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

如果 `verifyRagSharedSchema` 报错，不应绕过 Gate。应检查共享 ObjectBox Meta Model、Entity UID、Manifest Schema 指纹和候选 Store 是否来自同一版本。

---

## 8. 延伸文档

- [RAG Indexer 子项目说明](rag-indexer/README.md)
- [当前试运行语料说明](rag-indexer/corpus/model_y_2026_refresh_trial/README.md)
- [RAG 总体设计](../docs/plan_overall/rag/vehicle_agent_rag_design.md)
- [Parent-Child V2 实施计划](../docs/plan_overall/eval/08-rag-parent-child-retrieval-eval-v2-implementation-plan.md)
- [Parent Evidence Eval 报告](../docs/testresult/rag/parent_evidence_eval_v2_latest_report.md)

**当前结论：** AIAgent RAG 已形成从多格式静态资料、结构恢复、Parent-Child 分块、Embedding/BM25、ObjectBox Bundle 到 Android 只读 Hybrid Retrieval 和 Agent Tool Calling 的完整 Demo 链路。当前重点是继续补充高质量资料和评测覆盖，而不是将 `TEST_ONLY` Demo 候选描述为正式量产知识库。
