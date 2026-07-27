# RAG Parent-Child 分块、Parent Evidence 与 Eval V2 详细实施计划

> 文档状态：已获项目负责人批准，执行中
> 计划类型：Goal 模式执行计划  
> 适用工程：`AIAgent` Monorepo 内的 `tools/rag-indexer`、`rag-schema`、`app` 与 RAG 评测资产  
> 上位设计：`docs/plan_overall/rag/vehicle_agent_rag_design.md`  
> 现状台账：`docs/plan_overall/rag/rag_execution_status.md`  
> 本计划不授权直接发布正式 Bundle，也不授权将 TEST_ONLY 资产复制到主 APK

> **2026-07-24 修订说明：** 旧计划 Phase 0 保持有效；Phase 1 的 PDF Block 语义恢复、Parent 内 Child 划分部分暂由 `09-rag-pdf-line-paragraph-child-rechunking-plan.md` 覆盖。必须先完成新的两列行读取、Parent Paragraph Reconstruction、Paragraph Embedding 语义合并和人工审核，再继续本计划 Phase 2。旧 Phase 1 生成的 Chunk、Embedding、Bundle 不得作为新策略基线。

> **2026-07-25 标题检索协议补充：** 09 计划完成后，08 的剩余实现必须增加 Parent 二级标题参与检索的统一协议。该补充不要求重新执行 09 的 PDF/HTML 解析、Parent 划分或 Child 分块；沿用 09 已生成且 ID 稳定的 Parent/Child 产物，仅重新生成检索索引和 Bundle。离线端与 Android 端必须共同遵守以下规则：
>
> - 每个 Parent 显式确定 `parentTitle`，其语义为该 Parent 对应的二级标题；完整一级/二级标题路径仍保存在 `headingPath` 元数据中。若输入缺少二级标题，按 `headingPath` 的最后有效标题回退，并在构建诊断中记录回退原因。
> - Dense 向量输入使用“二级标题 + Child 正文”固定模板。模板版本递增，Embedding Cache Key 必须包含模板版本和完整输入文本；已有 Child 正文和 ID 不变时不重跑分块，只重新生成向量。
> - BM25 使用 TITLE 与 BODY 两个独立字段：分别计算字段分词、TF、DF、文档长度和平均文档长度；禁止把标题与正文直接拼接成单字段后计算长度。初始排序公式为 `BM25_TITLE × 2.0 + BM25_BODY × 1.0`，权重通过版本化配置传递到离线评测和 Android 查询端。
> - ObjectBox Bundle、Manifest、Schema Fingerprint 和 Android Gateway 必须同步升级，确保字段 posting、字段长度和元数据版本一致；旧单字段词法索引不得与新字段索引混用。
> - 本补充完成后，继续执行原 Phase 2 及后续阶段：RRF 候选统一合并与去重、Child Rerank、Child→Parent 映射、Parent Evidence 预算选择、Eval V2 和人工复核。标题协议变更后的向量/BM25 重建属于 08 的索引阶段，不视为重新执行 09。

> **执行台账（2026-07-27）：** Phase 2 的标题检索 Bundle、Child→Parent Evidence、Android 候选去重和 Parent 预算已实现；当前人工审核后的 Eval V2 为 33 条唯一 Query（DEV 9、TEST 24），RRF、Rerank 与 7 模式消融均已成功执行。Phase 4 的 Android JVM、Debug APK、Lint 和 Automotive AVD 已通过；DEV/TEST 置信度阈值已完成一次校准。所有 Bundle 继续保持 `TEST_ONLY`，本台账不构成发布批准。

> **评测规模决策补充（2026-07-27）：** 计划原始目标为至少 50 条，但项目负责人已确认当前以最新人工审核后的 33 条作为“先跑通”验收集；本轮不擅自补题或扩充样本。该规模偏差已记录，后续若要进行正式质量结论或发布审批，仍需补足覆盖面并重新冻结 DEV/TEST。当前数据集没有 `NO_EVIDENCE` 样本，因此 No-Evidence 阈值仅保留受控默认策略，未声称完成经验校准。

---

## 1. 文档目的

本计划用于指导子 Agent 以 Goal 模式完成以下四项相互依赖的改造：

1. 将现有“遇到标题即切 Parent、按最大 Token 聚合 Child”的 V1 分块，升级为“小节级 Parent + 检索级 Child”的结构递归与语义混合分块。
2. 将现有“Rerank 后直接返回 Child Evidence”的运行链升级为：

   ```text
   Query
     → Dense / BM25 检索 Child
     → RRF Fusion
     → Child 候选去重
     → Rerank Child
     → Top Child 映射 Parent
     → Parent 聚合去重
     → 完整 Parent 预算选择
     → Parent Evidence + retrievalConfidence
   ```

3. 将现有只支持 `expectedChunkIds` 的 Eval V1 升级为以“最终 Parent Evidence 是否足以回答问题”为核心的 Eval V2。
4. 使用重新构建的 V2 Bundle 和重新人工标注的高质量评测集，重新评估 RRF、Rerank、候选去重、Parent Evidence 与置信度，不继承旧评测的错误结论。

本计划是对既有 RAG 实现的定向改造，不重做已经完成并通过验证的以下能力：

- PDF、静态 HTML、Markdown 的安全输入边界；
- DashScope 文档 Embedding 批处理、缓存恢复和向量校验；
- ObjectBox 预构建数据库、Manifest Hash、原子发布与 Android Asset 安装；
- VehicleProfile、Knowledge Scope 与 Metadata Eligibility；
- RAG Tool、REQUIRED ToolLoop、CitationGuard、Memory 策略、Deadline、取消和 Trace 总体架构。

---

## 2. 规范优先级与冲突处理

执行本计划时按以下优先级处理冲突：

1. 项目负责人在本轮讨论中确认的 Parent-Child、Rerank、Parent Evidence 和 Eval V2 决策；
2. 本计划；
3. `vehicle_agent_rag_design.md` 修订后的条款；
4. 既有离线端与 Android 端实施计划；
5. 当前代码行为。

当前总体设计中仍存在以下旧语义，执行前必须先修订，不能让子 Agent 同时遵守两套冲突规则：

- 旧设计描述“命中 Child 后补必要前后文”，新设计要求 Rerank 只处理 Child，最终完整 Parent 才作为 Evidence；
- 旧设计的最终 Evidence 基线为 5 个 Child，新设计为通常 2～3 个 Parent、最多 4 个 Parent；
- 旧设计未规定 Parent 置信度，新设计要求 Parent Evidence 携带检索置信度；
- 旧 Eval 以 Child ID 命中为主要口径，新 Eval 以 Parent Evidence 是否足以回答为主要口径。

若执行中发现本计划与已确认决策仍有冲突，必须暂停当前 Goal，记录冲突位置并询问项目负责人；不得以当前代码“已经这样写了”为理由覆盖新设计。

---

## 3. 当前实现基线

### 3.1 工作区基线

计划编写时工作区已有以下与 Rerank 诊断相关的未提交改动：

```text
M  app/src/main/java/com/hirain/aiagent/AIAgentService.kt
M  tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/cli/RagIndexerCommand.java
M  tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/OfflineHybridEvaluator.java
?? tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/cli/EvaluateRerankCommand.java
?? tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RerankDiagnosticReportWriter.java
?? tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/evaluation/RerankManualReviewReportWriter.java
```

这些文件属于前序检索质量诊断工作，执行者必须：

1. 在首个 Goal 开始时重新执行 `git status --short`；
2. 阅读并区分这些改动与本计划的新改动；
3. 不删除、不回滚、不覆盖；
4. 将可复用的 Rerank 真实调用、索引校验和人工审核能力纳入 V2；
5. 若发现代码未编译或与本计划冲突，先记录现状，再以最小改动修复，不使用 `git reset --hard`、`git checkout --` 等破坏性命令。

### 3.2 当前分块基线

当前离线端主要行为：

- `HeadingAwareParentChunker` 每遇到一个 `HEADING` 就结束当前 Parent；
- `ChildChunkSplitter` 按 Block 和 `maxChildTokens=256` 聚合；
- Heading、Warning、超长 Block 被直接作为原子 Child；
- 引用位置不连续时立即切断；
- `overlapTokens=32` 已存在于配置，但当前语义分块和回退式 overlap 规则尚未实现；
- HTML/Markdown 已形成扁平 `headingPath`，但 `StructuredBlock` 没有显式结构层级或列表组元数据；
- PDF 仅判断某一行是否为标题，`SourceLocator.headingPath` 当前为空，没有形成递归标题路径；
- Parent 与 Child 都写入同一 `KnowledgeChunkEntity`，Child 保存 `parentChunkId`；
- Dense 只检索 Child，Parent 不参与 HNSW；
- BM25 只分析 `Child.text`，尚未将 `sectionPath` 纳入词法文档；
- Embedding 模板已经包含文档标题、标题路径、类型和 Child 正文。

### 3.3 当前 Android 检索基线

当前 `HybridRetrievalCoordinator`：

- Lexical Top20；
- Dense Top20；
- RRF 按 `chunkId` 合并；
- 直接将全部融合 Child 构造成 Rerank 候选；
- Rerank 输入为 `headingPath + Child content`；
- Rerank 后仍将 Child 内容映射为 `RetrievalEvidence`；
- 没有批量查询 Parent；
- 没有 Child 近重复去重；
- `EvidenceSelector` 仍对 Child Evidence 去重和预算裁剪；
- `EvidenceDeduplicator` 的 Key 包含唯一 `retrievalEvidenceId`，不同 Child 即使正文相同也不会真正去重；
- `VehicleKnowledgeEvidence` 尚无 `sectionPath` 和 `retrievalConfidence`。

### 3.4 当前 Eval 基线

当前 Eval V1：

- Schema 仅支持 `expectedChunkIds`；
- 主要指标为 Recall@1/@3/@5 和 MRR；
- 评测目标是 Child；
- 旧 V4 有 20 条问题；
- 人工审核确认 9 条 Rerank 失分样本中：
  - 6 条原始标注错误；
  - 1 条存在等价答案；
  - 1 条 Rerank 确实降级；
  - 1 条两边证据都不足。

因此旧 `Recall@5=0.55` 不能继续作为 Rerank 的有效结论。旧数据只能作为历史诊断资产，不得直接转换成 V2 Ground Truth。

---

## 4. 已确认的最终设计

### 4.1 Parent 定义

Parent 是文档中由明确小节边界定义的最小语义完整小节：

- 大章节仅作为结构 Metadata，不作为 Parent；
- Parent 主题单一、内容完整；
- 不跨兄弟小节合并；
- 短但完整的小节必须保留，不能为了达到最小 Token 人工拼接其他小节；
- 超长小节可以拆为多个 Parent，但每个拆分后的 Parent 必须独立可理解，并保留相同 `sectionPath`、连续分段序号和可靠来源范围；
- Parent 不参与 Dense、BM25 或默认向量召回；
- Parent 只在 Child Rerank 完成后恢复并作为最终 Evidence。

### 4.2 Child 定义

Child 是 Parent 内的检索定位单元：

- 短 Parent：`1 Parent → 1 Child`；
- 长 Parent：`1 Parent → N Child`；
- Child 必须保存 `parentId`、`sectionPath`、`chunkIndex`、`chunkType` 和 `SourceLocator`；
- Child 应语义完整；
- 默认不重叠；
- 只有超长内容无法继续按自然语义拆分、必须进入长度兜底时，才允许 5%～10% 的少量 overlap；
- overlap 只能发生在同一 Parent 内；
- overlap 应尽量以完整句子为单位，不复制半句，不跨 Parent。

### 4.3 分块优先顺序

```text
标题结构
  → 最小自然小节
  → 子主题
  → 自然段组
  → 完整列表 / 操作步骤
  → 完整警告
  → 条件—结果关系
  → 完整句子
  → 长度兜底 + 同 Parent 内少量 overlap
```

禁止切断：

- 单个完整列表项；
- 一组必须连续理解的操作步骤；
- 警告的条件、行为和后果；
- “如果/当……则/需要……”条件—结果关系；
- 表格标题、表头、单位和其对应数据。

若一个真正不可再分的原子语义单元超过硬上限，构建端不得静默截断。必须产生可审计诊断，并在 `APPROVED` Gate 前人工决定是允许例外、修复 Parser、增加 Corpus Override 还是调整原始资料边界。

### 4.4 参数基线

以下参数是 V2 的初始 TEST_ONLY 基线；正式阈值仍须通过 Eval V2 校准：

| 参数 | V2 初始规则 |
|---|---:|
| Parent 理想最小值 | 150 Tokens；不是硬下限 |
| Parent 软上限 | 1200 Tokens |
| Parent 硬上限 | 约 2000 Tokens |
| Child 理想范围 | 160～320 Tokens |
| Child 目标值 | 约 256 Tokens |
| Child 软上限 | 384 Tokens |
| Child 硬上限 | 约 512 Tokens |
| Child overlap | 默认 0；仅长度兜底时为 5%～10% |
| Dense Top-K | 默认 20，可评测到 30 |
| BM25 Top-K | 默认 20，可评测到 30 |
| RRF 候选池 | 默认最多 30 Child |
| RRF `k` | 60 |
| Child Rerank | 默认 15，最大 20 |
| Rerank 输入 | `sectionPath + Child 正文` |
| Rerank 语义预算 | 初始 6000 Tokens，硬保护不超过 7000 Tokens |
| 最终 Parent 目标数量 | 通常 2～3 |
| 最终 Parent 最大数量 | 4 |
| Parent Evidence 语义预算 | 初始上限 5000 Tokens |

Token 预算必须由跨端一致、版本化的估算算法计算。网络请求和 ToolResult 仍保留独立字符/字节硬保护，但字符上限不得替代 Token 语义预算。

### 4.5 检索与 Evidence

唯一标准流程：

```text
Query
  → Query Normalize
  → Dense Top-K Child + BM25 Top-K Child
  → Metadata Eligibility
  → RRF
  → 限制 Fusion Candidate 数量
  → 按 Chunk ID 合并
  → 完全重复 / 高度相似 Child 去重
  → 选择最多 15 个 Child 进入 Rerank
  → Rerank Child
  → Child 按 parentId 映射
  → 同 Parent 聚合，只保留一次
  → Parent 排名采用该 Parent 下最高 Child Rerank Score
  → 同分时以最佳 Child 的 RRF Rank、Parent ID 稳定排序
  → 批量读取完整 Parent
  → 按 5000 Token 与最多 4 个 Parent 进行完整 Parent 选择
  → 返回 Parent Evidence
```

最终 Parent 不截断。若加入下一个完整 Parent 会超过 Evidence 预算，则停止加入该 Parent，不允许把 Parent 截成半段。

### 4.6 Parent 检索置信度

`retrievalConfidence` 表示检索相关性置信度，不表示答案事实正确概率。

计算输入至少包含：

- Parent 最佳 Child 的 Rerank Score；
- 当前 Parent 与下一名 Parent 的 Score Margin；
- 排名来源：`RERANK` 或 `RRF_FALLBACK`；
- 是否发生云调用降级；
- 可选的同 Parent 支持 Child 数量，仅作诊断，不参与 Parent 主排序。

模型可见 Evidence 只暴露稳定等级，例如：

- `HIGH`
- `MEDIUM`
- `LOW`
- `UNASSESSED`

原始 Rerank Score、Margin、Child ID 和内部 Rank 保留在内部结果、Trace 与评测报告，不直接伪装成百分比展示给模型或用户。

在 Eval V2 完成阈值校准前：

- TEST_ONLY 运行可以输出原始诊断；
- 模型可见等级使用 `UNASSESSED`，或仅在有受控临时阈值的开发构建中输出并明确标注 TEST_ONLY；
- 禁止把未经校准的 `0.92` 描述为“92% 正确”。

### 4.7 Eval V2 主口径

主指标从 Child ID 命中升级为：

> Top Parent Evidence 是否完整覆盖至少一个人工确认、足以回答问题的可接受证据集合。

Child Recall 只作为定位问题的诊断指标，不再决定最终 RAG 是否成功。

---

## 5. 工作范围

### 5.1 必须完成

- 修订 RAG 总体设计中与新 Parent-Child 链路冲突的章节；
- 版本化 Token 估算、Chunk 配置和 Manifest Chunking 协议；
- 补齐 PDF/HTML/Markdown 的结构层级与语义组边界；
- 实现 V2 Parent 划分；
- 实现 V2 Child 语义分块与仅兜底 overlap；
- 扩展 Chunk 审核报告与质量统计；
- 重新生成 Stable ID、Embedding、BM25 和 ObjectBox Bundle；
- 在 RRF 后增加完全重复和高度相似 Child 去重；
- 保持 Rerank 只处理 Child；
- 实现 Child → Parent 聚合、Parent 批量读取和完整 Parent Evidence；
- 实现 Parent Evidence Token 预算；
- 实现 Parent retrievalConfidence 数据结构、诊断和校准入口；
- 实现 Eval V2 Schema、加载器、指标、报告和人工审核页；
- 创建并人工标注至少 50 条高质量 Eval V2 样本；
- 执行离线、Android JVM、AVD 跨端验证；
- 更新执行台账和测试报告。

### 5.2 明确不做

- 不引入在线 LLM 自动分块；
- 不建设动态 HTML 抓取或渲染；
- 不新增 OCR；
- 不重做已经通过的 PDF/HTML/Markdown 安全输入体系；
- 不让 Parent 进入 Dense/HNSW 或 BM25 默认召回；
- 不把完整 Parent 发送给 Rerank；
- 不把相邻 Child 或完整 Parent 默认加入 Rerank 输入；
- 不允许 overlap 跨 Parent；
- 不在没有人工 Eval 的情况下锁定置信度阈值；
- 不把旧 20 条 Eval 的 Chunk ID 自动迁移成 V2 Ground Truth；
- 不自动提升 Bundle 为 `APPROVED`；
- 不复制 TEST_ONLY Bundle 到 `app/src/main/assets`；
- 不修改 API Key、凭证或本地机器配置；
- 不扩展通用 PC Eval/TestApp 架构，本计划只负责 RAG 检索质量评测。

### 5.3 ObjectBox 边界

现有 `KnowledgeChunkEntity` 已具备：

- `chunkId`
- `chunkLevel`
- `parentChunkId`
- `headingPath`
- `content`
- `chunkType`
- `sectionOrdinal`
- `ordinal`
- `tokenEstimate`
- `SourceLocator` 相关字段

V2 第一版应优先复用这些字段：

- Parent 的 `chunkLevel=PARENT`；
- Child 的 `chunkLevel=CHILD`；
- Child `parentChunkId` 映射 Parent；
- `headingPath` 承载 `sectionPath`；
- Child `ordinal` 承载 Parent 内 `chunkIndex`；
- Parent `ordinal` 承载文档内稳定顺序。

因此默认不修改 ObjectBox Entity 和 Meta Model。若实现中发现必须新增持久化字段，必须暂停 Goal，说明：

1. 现有字段为什么无法表达；
2. 是否可放入 Manifest、Build Report 或运行时派生；
3. Schema UID 如何保持；
4. 旧 Bundle 如何拒绝或兼容；
5. Android 与 CLI 如何同步。

未经项目负责人确认，不得修改 `rag-schema/objectbox-models/default.json` 或重新生成 UID。

---

## 6. Goal 模式执行规范

### 6.1 Goal 粒度

每个 Task 对应一个独立 Goal。一个 Goal 必须：

1. 只完成当前 Task；
2. 开始时检查工作区和上游产物；
3. 先说明现状、假设和最小实现；
4. 再修改文件；
5. 运行 Task 指定测试；
6. 记录命令、结果、产物和未关闭风险；
7. 只有验收真实通过才标记 `COMPLETED`。

### 6.2 每个 Goal 的固定记录

每个 Goal 在 `rag_execution_status.md` 或本计划指定的新 V2 执行台账中记录：

```text
Goal ID
来源 Task
开始/结束时间
修改文件
设计决策
执行命令
测试结果
生成产物
人工审核状态
风险
唯一下一 Goal
```

### 6.3 必须暂停并询问的情况

- Parser 无法可靠恢复 PDF 标题层级；
- 同一自然小节存在多个合理 Parent 划分且会显著改变检索结果；
- 原子列表、步骤、警告或表格超过 Parent/Child 硬上限；
- 需要新增 ObjectBox 字段或修改 Meta Model；
- 高度相似阈值会删除人工认为互补的 Child；
- Parent 完整内容超过 Evidence 总预算；
- 需要截断 Parent 才能返回；
- Eval 标注者无法判断某问题是否可回答；
- 一个问题需要多个 Parent 联合回答但现有 Schema 表达不了；
- Rerank Score 分布无法支持稳定置信度等级；
- 需要将真实正文、Query 或审核备注写入可提交报告；
- 需要覆盖已有 Bundle、Eval 或人工审核文件。

---

## 7. Phase 总览

| Phase | 目标 | 主要产物 |
|---|---|---|
| Phase 0 | 冻结基线、修订协议、建立 V2 配置与共享 Token 契约 | 修订设计、V2 配置模型、跨端 Token Golden |
| Phase 1 | 实现结构递归 Parent 与语义 Child | V2 Chunker、质量诊断、人工 Chunk 审核页 |
| Phase 2 | 重建索引并实现 Child 检索到 Parent Evidence | V2 TEST_ONLY Bundle、候选去重、Parent 聚合、置信度结构 |
| Phase 3 | 重构 Eval 并校准检索质量与置信度 | Eval V2 Schema、至少 50 条样本、RRF/Rerank 报告 |
| Phase 4 | 跨端总装、AVD 验证和文档收口 | Android 验收报告、最终台账、发布前风险清单 |

Phase 之间严格顺序执行。Phase 1 的 Chunk 人工审核未通过前，不得为全量真实资料重新请求 Embedding；Phase 3 的人工 Eval 未完成前，不得锁定置信度阈值或发布质量结论。

---

# Phase 0：基线、上位协议与版本化配置

## 目标

建立唯一 V2 规范源，保护当前 V4 和未提交诊断工作，避免后续代码在旧设计与新设计之间漂移。

## Task 0.1：冻结工作区与历史评测基线

### Goal ID

`RAG-EV2-G001`

### 工作步骤

1. 执行并记录：

   ```powershell
   git status --short
   ```

2. 核对前序 Rerank 诊断文件能否编译，不能删除现有改动。
3. 记录以下历史资产路径和 Hash（若文件存在）：
   - V4 Bundle；
   - 旧 20 条 Eval；
   - RRF 报告；
   - Rerank 诊断报告；
   - 人工审核 HTML；
   - 用户导出的人工审核 JSON。
4. 在 `rag_execution_status.md` 增加 V2 改造起点记录。
5. 明确旧资产全部为历史 TEST_ONLY，不允许覆盖。

### 修改文件

- `docs/plan_overall/rag/rag_execution_status.md`

### 边界

- 不修改业务代码；
- 不重跑云端评测；
- 不移动真实资料；
- 不提交本地 `work/` 正文审核产物。

### 验收

- 工作区变更来源清楚；
- 历史产物路径和状态可恢复；
- 唯一下一 Goal 为 `RAG-EV2-G002`。

---

## Task 0.2：修订 RAG 总体设计与跨端协议

### Goal ID

`RAG-EV2-G002`

### 修改文件

- `docs/plan_overall/rag/vehicle_agent_rag_design.md`
- 必要时同步：
  - `docs/plan_overall/rag/vehicle_agent_offline_rag_plan.md`
  - `docs/plan_overall/rag/vehicle_agent_android_rag_plan.md`
  - `docs/plan_overall/rag/vehicle_agent_rag_goal_execution_schedule.md`

### 必须修订的章节

- `6.6 ParentChunk 与 ChildChunk`
- `6.7 RetrievalEvidence 与 VehicleKnowledgeEvidence`
- `8 Chunk 协议`
- `9.2 Embedding 输入`
- `10 ObjectBox 数据协议`
- `14 Lexical/BM25 协议`
- `15 Hybrid Retrieval 协议`
- `16 RagResult 与 ToolResult`
- `19.4 Evidence 预算`
- `20 Trace`
- `22 版本与兼容`
- `23 测试与评测`

### 修订要求

1. 写入本计划第 4 章的唯一标准链路。
2. 删除或标记废弃“Rerank 后拼相邻 Child”的旧行为。
3. 明确 Parent 不参与默认召回。
4. 明确最终 Evidence 是完整 Parent。
5. 明确 Parent 排名取最高 Child Rerank Score。
6. 明确模型侧只暴露置信度等级，不暴露内部 Score 百分比。
7. 明确 V2 Eval 以 Parent Evidence 集合覆盖为主指标。
8. 明确 Chunk 参数变化必须重新生成 Embedding、BM25 和 Bundle。

### 验收

- 文档内不再同时存在“Child Evidence”和“完整 Parent Evidence”两套最终语义；
- 三份旧计划若仍保留 V1 描述，必须添加被本计划覆盖的说明；
- 不改代码。

---

## Task 0.3：建立跨端 TokenEstimator V2 契约

### Goal ID

`RAG-EV2-G003`

### 创建文件建议

- `rag-schema/src/main/java/com/hirain/aiagent/rag/contract/RagTokenEstimator.java`
- `rag-schema/src/main/java/com/hirain/aiagent/rag/contract/RagTokenEstimate.java`
- `rag-schema/test-vectors/token-estimator-v2.json`
- `tools/rag-indexer/src/test/java/.../contract/RagTokenEstimatorGoldenTest.java`
- `app/src/test/java/com/hirain/aiagent/rag/contract/RagTokenEstimatorGoldenTest.java`

### 修改文件

- `tools/rag-indexer/src/main/java/.../chunk/TokenEstimator.java`
- `app/src/main/java/com/hirain/aiagent/rag/policy/EvidenceBudgetPolicy.java`
- `app/build.gradle.kts` 或现有共享源码接入位置（仅在已有共享方式需要时）
- `tools/rag-indexer/build.gradle.kts`（仅在已有共享方式需要时）

### 实现要求

1. 使用一套版本化、确定性算法。
2. CJK、Latin、数字、标点和换行必须有明确计数规则。
3. CLI 与 Android 对同一 Golden 文本必须输出完全相同结果。
4. 不调用远程 Tokenizer。
5. 算法版本进入 Chunk 配置、Manifest 或兼容指纹。
6. 旧 `TokenEstimator.VERSION=1` 的历史语义必须可追溯；V2 不得静默改变旧 Bundle 的 Hash 解释。

### 测试

- 纯中文；
- 中英混合；
- 数字、单位和车型名称；
- 标题路径；
- 列表、步骤、警告；
- 空白与换行；
- 超长文本；
- CLI/Android Golden 一致。

---

## Task 0.4：定义 V2 Chunk 配置与 Manifest 兼容协议

### Goal ID

`RAG-EV2-G004`

### 修改文件

- `tools/rag-indexer/schemas/rag-build.schema.json`，或保留 V1 并新增 `rag-build-v2.schema.json`
- `tools/rag-indexer/src/main/java/.../config/ChunkingConfig.java`
- `tools/rag-indexer/src/main/java/.../config/RagBuildConfig.java`
- `tools/rag-indexer/src/main/java/.../config/ConfigLoader.java`
- `tools/rag-indexer/src/main/java/.../config/ConfigValidator.java`
- `tools/rag-indexer/src/main/java/.../artifact/ManifestBuilder.java`
- `tools/rag-indexer/src/main/java/.../artifact/KnowledgeBundleManifest.java`
- `rag-schema/contracts/knowledge-bundle-manifest.schema.json`
- `app/src/main/java/com/hirain/aiagent/rag/store/KnowledgeBundleManifest.java`
- `app/src/main/java/com/hirain/aiagent/rag/store/KnowledgeBundleManifestParser.java`
- 对应 CLI/Android 配置与 Manifest 测试

### V2 Chunk 配置至少包含

```text
configVersion
tokenEstimatorVersion
parentIdealMinTokens
parentSoftMaxTokens
parentHardMaxTokens
childIdealMinTokens
childTargetTokens
childSoftMaxTokens
childHardMaxTokens
fallbackOverlapMinRatio
fallbackOverlapMaxRatio
tableRowsPerChild
semanticStrategyVersion
```

### 兼容策略

1. V1 配置继续用于旧 Fixture 和历史测试。
2. V2 真实 Bundle 必须声明 `chunking.configVersion=2`。
3. ObjectBox Entity 格式不因分块算法变化而自动升级。
4. Manifest 必须记录显式参数或可审计配置 Hash。
5. Android 对未知 Chunk 配置版本 fail closed。
6. 旧 Android 不得误把 V2 Bundle 当成 V1 激活。

### Phase 0 测试门禁

```powershell
cd tools/rag-indexer
.\gradlew.bat clean test check --console=plain

cd ..\..
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Phase 0 完成定义：

- 上位协议无冲突；
- TokenEstimator 跨端一致；
- V1/V2 配置兼容行为有测试；
- 未修改 ObjectBox Meta Model；
- 唯一下一 Goal 为 `RAG-EV2-G101`。

---

# Phase 1：结构递归 Parent 与语义 Child

## 目标

先在不请求全量 Embedding 的情况下，生成可人工审阅、可量化的 Parent/Child V2 结果。

## Task 1.1：扩展统一结构 Block 元数据

### Goal ID

`RAG-EV2-G101`

### 当前问题

- `StructuredBlock` 只有 `BlockType`、文本和 Locator；
- HTML `<li>` 被当作普通 Paragraph；
- Markdown List Item 没有独立类型和列表组标识；
- PDF 只判断“是不是标题”，没有标题层级和路径；
- Chunker 无法可靠判断完整列表、步骤组或结构节点。

### 创建文件建议

- `tools/rag-indexer/src/main/java/.../model/BlockStructure.java`
- `tools/rag-indexer/src/main/java/.../model/BlockRole.java`
- `tools/rag-indexer/src/main/java/.../model/SequenceType.java`
- `tools/rag-indexer/src/main/java/.../parser/pdf/PdfHeadingLevelResolver.java`
- `tools/rag-indexer/src/main/java/.../parser/pdf/PdfHeadingPathTracker.java`

### 修改文件

- `model/StructuredBlock.java`
- `model/BlockType.java`
- `parser/html/HtmlStructureWalker.java`
- `parser/html/HtmlHeadingPathTracker.java`
- `parser/markdown/MarkdownDocumentParser.java`
- `parser/markdown/MarkdownHeadingPathTracker.java`
- `parser/pdf/PdfBlockAssembler.java`
- `parser/pdf/PdfHeadingDetector.java`
- Warning、Table 与 Locator 相关 Parser
- 三种格式对应测试

### 实现要求

`BlockStructure` 至少能表达：

- heading level / structure depth；
- 当前完整 `sectionPath`；
- list/step group ID；
- list depth；
- 当前 Block 是否属于不可从中间切断的语义组；
- 文档内稳定 ordinal。

PDF 标题层级推断顺序：

1. 明确编号深度，例如 `1`、`1.2`、`1.2.3`；
2. 中文编号模式；
3. 字号层级；
4. 字体样式与位置，仅作辅助；
5. 无法可靠判断时产生诊断，不得伪造深层路径。

### 边界

- 不改变原始文档；
- 不执行 LLM 分类；
- 不把 HTML/Markdown 路径解析逻辑复制到 Chunker；
- Parser 负责提供结构事实，Chunker 负责使用结构事实。

### 测试

- HTML h1～h6、跳级标题、嵌套列表、连续和分离列表；
- Markdown Heading、List、BlockQuote、Code、GFM Table；
- PDF 编号标题、字号标题、跨页标题和误判正文；
- 大章节只进入路径；
- 标题空壳不生成正文 Parent；
- SourceLocator 保持可追溯。

---

## Task 1.2：构建文档 Section Tree 与小节级 Parent

### Goal ID

`RAG-EV2-G102`

### 创建文件建议

- `chunk/DocumentSection.java`
- `chunk/DocumentSectionTreeBuilder.java`
- `chunk/SectionContent.java`
- `chunk/SectionParentChunker.java`
- `chunk/ParentSemanticSplitter.java`
- `chunk/SemanticUnit.java`
- `chunk/SemanticUnitBuilder.java`

### 修改文件

- `chunk/DocumentChunker.java`
- `chunk/HeadingAwareParentChunker.java`：替换、委托或明确标记为 V1
- `chunk/ParentChunk.java`
- `chunk/ChunkResult.java`
- `chunk/ChunkDiagnostic.java`
- `pipeline/ChunkStageRunner.java` 或实际 Chunk Pipeline 入口

### Parent 算法

1. 根据 Block 的结构路径构建 Section Tree。
2. 大章节节点仅保留 Metadata。
3. 对有直接正文的最小自然小节形成 Parent Candidate。
4. 非叶节点在第一个子节前存在独立前言时：
   - 前言语义完整则形成独立 Parent；
   - 仅有导航或过渡句则附属于最相关子节或记录诊断；
   - 不跨兄弟子节合并。
5. 小于 150 Tokens 但语义完整的小节直接保留。
6. 150～1200 Tokens 优先整节保留。
7. 1200～2000 Tokens：
   - 主题仍单一且原子组完整时可以保留；
   - 存在明显独立子主题时优先语义拆分。
8. 超过 2000 Tokens 必须按语义单元递归拆分。
9. 拆分后的 Parent 保留：
   - 相同 `sectionPath`；
   - 独立 Parent ID；
   - 文档内稳定 Parent ordinal；
   - 同小节 segment index；
   - 合并后的可靠 SourceLocator。

### 原子语义组

- 完整 Warning；
- 条件、行为、结果；
- 不可分离操作步骤；
- 表格标题、表头、单位和对应行；
- 代码块及其解释；
- 定义项与定义正文。

### 失败策略

真正不可分的语义组超过 Parent 硬上限时：

- 生成 `PARENT_ATOMIC_UNIT_OVERSIZED`；
- TEST_ONLY 可以进入本地审核，但报告必须显著标记；
- APPROVED 构建默认拒绝，除非 Corpus Override 经人工批准。

### 测试

- 小节短 Parent；
- 大章节不作为 Parent；
- 同一章节多个小节；
- 一个长小节拆多个 Parent；
- 不跨小节；
- 前言处理；
- Warning/条件结果不切断；
- Locator 合并失败时安全拆分；
- 顺序与 ID 输入确定。

---

## Task 1.3：实现 Parent 内语义 Child 与兜底 overlap

### Goal ID

`RAG-EV2-G103`

### 创建文件建议

- `chunk/SemanticChildSplitter.java`
- `chunk/SemanticBoundaryPolicy.java`
- `chunk/LengthFallbackSplitter.java`
- `chunk/FallbackOverlapPolicy.java`
- `chunk/AtomicGroupPolicy.java`

### 修改文件

- `chunk/ChildChunkSplitter.java`：保留为 V1 或委托 V2
- `chunk/ChildChunk.java`
- `chunk/TableChunkSplitter.java`
- `chunk/WarningCohesionPolicy.java`
- `chunk/ChunkBoundaryPolicy.java`
- `chunk/DocumentChunker.java`

### Child 算法

1. Parent 不超过 Child 软上限且语义单一：
   - `Child = Parent`；
   - `chunkIndex=0`。
2. Parent 较长：
   - 先按子主题；
   - 再按自然段组；
   - 再按步骤/列表组；
   - 再按条件—结果组；
   - 最后按完整句子分组。
3. 目标约 256 Tokens，优先保持 160～320 Tokens。
4. 384 Tokens 为软上限：
   - 完整语义需要时可超过；
   - 记录原因。
5. 约 512 Tokens 为硬上限：
   - 优先继续语义拆分；
   - 无法拆分时产生诊断。
6. 默认 overlap 为 0。
7. 只有进入 `LengthFallbackSplitter` 时允许 overlap。
8. overlap 选择同 Parent 中边界前后最少量的完整句子，使 overlap 占新 Child 约 5%～10%。
9. 不允许：
   - 跨 Parent；
   - 复制半句；
   - 复制整个大段；
   - 让 overlap 成为重复候选的主要正文。

### Child 必须携带

```text
parentOrdinal / parentId（稳定 ID 阶段补入）
chunkIndex
sectionPath
chunkType
content
SourceLocator
tokenEstimate
splitReason
overlapTokenCount
```

其中 `splitReason`、`overlapTokenCount` 可以只存在于离线领域模型和报告，不要求写入 ObjectBox，除非后续证明运行时必须使用。

### 测试

- `1 Parent → 1 Child`；
- 长 Parent 多 Child；
- 默认无 overlap；
- 仅兜底 overlap；
- overlap 比例边界；
- 不跨 Parent；
- 完整列表和步骤；
- Warning；
- 条件结果；
- 表格行组重复表头；
- 不产生空 Child；
- 每个 Child 独立可理解。

---

## Task 1.4：扩展 Chunk 质量分析与本地审核页

### Goal ID

`RAG-EV2-G104`

### 创建文件建议

- `report/ChunkQualityAnalyzer.java`
- `report/ChunkQualityReport.java`
- `report/ChunkQualityReportWriter.java`
- `report/ChunkQualityFinding.java`

### 修改文件

- `report/ChunkReviewHtmlWriter.java`
- `report/ChunkBuildSummary.java`
- `report/BuildReport.java`
- `report/BuildReportCollector.java`
- CLI `validate` 或新增只读 `review-chunks` 入口

### 报告必须包含

- Parent 数、Child 数；
- Parent Token 分布；
- Child Token 分布；
- 小于理想最小值的数量与原因；
- 超过软/硬上限的数量与原因；
- `1 Parent → 1 Child` 比例；
- 每 Parent Child 数分布；
- fallback overlap 数量和比例；
- 原子超长项；
- 空壳标题；
- 重复 Parent/Child 内容 Hash；
- 每个 Parent 的 sectionPath、来源位置和完整正文；
- 每个 Child 的 parentId、chunkIndex、splitReason、overlap 和正文；
- HTML/Markdown/PDF SourceLocator。

### 人工 Gate

使用当前 Model Y 真实资料先执行 Parser + Chunk，不请求全量新 Embedding。项目负责人至少抽检：

- PDF 车辆手册；
- 保修 HTML；
- DIY 多 HTML；
- 服务中心 HTML；
- 短小节；
- 长操作步骤；
- Warning；
- 表格；
- 同一 Parent 多 Child。

只有人工确认结构和语义边界后，才允许进入 Phase 2 全量重建。

### Phase 1 测试门禁

```powershell
cd tools/rag-indexer
.\gradlew.bat clean test check installDist --console=plain
```

并生成新的、禁止覆盖的本地审核目录：

```text
corpus/model_y_2026_refresh_trial/work/runs/<newRunId>/
├── parser-review.json
├── parser-review.html
├── chunk-quality.json
└── chunk-review.html
```

Phase 1 完成定义：

- 三种格式结构路径可用；
- Parent/Child 分块符合 V2；
- 无未解释的 10～20 字碎片；
- 超限项全部可审计；
- 项目负责人人工确认；
- 唯一下一 Goal 为 `RAG-EV2-G201`。

---

# Phase 2：索引重建、Child Rerank 与完整 Parent Evidence

## 目标

使用已批准的 Chunk V2 重建 TEST_ONLY Bundle，并使离线评测和 Android 运行时都执行相同的 Child → Parent 语义。

## Task 2.1：稳定 ID、Entity 映射与 Bundle 协议适配

### Goal ID

`RAG-EV2-G201`

### 修改文件

- `chunk/StableIdGenerator.java`
- `pipeline/StableIdStageRunner.java`
- `pipeline/ChunkStableIds.java`
- `store/KnowledgeChunkEntityMapper.java`
- `store/StoreWriteModelBuilder.java` 或实际写模型入口
- `artifact/ManifestBuilder.java`
- `report/BuildReportCollector.java`
- 对应 Stable ID、Mapper、Store 测试

### 实现要求

1. Parent ID 至少由：
   - documentId；
   - sectionPath；
   - Parent segment index；
   - Parent canonical content Hash；
   - algorithmVersion
   生成。
2. Child ID 至少由：
   - parentId；
   - chunkIndex；
   - Child canonical content Hash；
   - algorithmVersion
   生成。
3. 同一输入、配置和 Parser 输出必须得到相同 ID。
4. 分块算法变化必须改变 Chunk 配置 Hash。
5. Parent embedding 继续为 null。
6. Child 才写入 1024 维向量和 lexical length。
7. `sectionPath` 编码到现有 `headingPath`。
8. 不修改 ObjectBox Schema。

### 测试

- 输入顺序扰动不改变稳定结果；
- segment index 变化改变 Parent ID；
- Child 内容或 chunkIndex 变化改变 Child ID；
- Parent/Child 关系完整；
- Parent 无向量；
- Store 中每个 Child 的 parentId 指向唯一 Parent。

---

## Task 2.2：统一 Dense 与 BM25 的 Child 文本表示

### Goal ID

`RAG-EV2-G202`

### 创建文件建议

- `lexical/LexicalDocumentRenderer.java`
- `rag-schema/test-vectors/retrieval-text-template-v2.json`

### 修改文件

- `chunk/EmbeddingTextRenderer.java`
- `pipeline/EmbeddingRequestStageBuilder.java`
- `lexical/LexicalDocument.java`
- `lexical/LexicalIndexBuilder.java`
- `pipeline/LexicalIndexStageRunner.java` 或实际入口
- Manifest embedding/lexical templateVersion
- 对应 Golden 测试

### 文本规则

Dense：

```text
文档标题
sectionPath
chunkType
Child 正文
```

BM25：

```text
sectionPath + Child 正文
```

要求：

- sectionPath 必须进入 Dense、BM25 和 Rerank；
- 不重复完整 Parent；
- 不把 SourceLocator、车型 Metadata 或内部 ID写入正文；
- 不通过无审计的重复标题词伪造 BM25 Boost；
- 如果需要标题权重，必须使用显式、可测试策略并经 Eval 校准。

### 测试

- 同一 Child 的三个输入均包含相同 sectionPath；
- 不含 Parent 正文；
- 模板输出稳定；
- Analyzer Golden 跨端一致；
- `lexicalDocumentLength` 与实际词项一致。

---

## Task 2.3：实现 RRF 后 Child 候选去重

### Goal ID

`RAG-EV2-G203`

### 创建文件建议

- Android：
  - `rag/ranking/FusionCandidateDeduplicator.java`
  - `rag/ranking/CandidateDeduplicationResult.java`
  - `rag/ranking/CandidateDeduplicationRecord.java`
  - `rag/ranking/ChildTextSimilarity.java`
- 离线端对应纯 Java实现或行为一致实现；
- `rag-schema/test-vectors/candidate-deduplication-v2.json`

### 修改文件

- `rag/ranking/ReciprocalRankFusion.java`
- `rag/retrieval/HybridRetrievalCoordinator.java`
- `tools/rag-indexer/.../evaluation/OfflineHybridEvaluator.java`
- `RagRetrievalConfig.java`
- RAG Trace DTO 和 Recorder

### 去重顺序

1. RRF 已按 `chunkId` 合并 Dense/BM25 同一候选；
2. 限制 Fusion Candidate 最多 30；
3. 标准化正文后按 SHA-256 删除完全重复；
4. 对剩余候选计算高度相似度；
5. 相似候选保留 RRF 排名更高者；
6. 记录被删除 Child、保留 Child、parentId、相似度与原因；
7. 去重后最多 15 个 Child 进入默认 Rerank，最大 20。

### 高度相似策略

第一版使用本地确定性、无新依赖的文本相似算法，例如规范化 Token Set Jaccard 或 CJK n-gram Jaccard。候选规模最多 30，允许 O(n²) 比较。

初始近重复阈值只能作为 TEST_ONLY 基线，并在 Eval V2 中校准。若某阈值会删除互补步骤，必须降低去重强度或增加保护规则，不得以减少候选数为目标删除有效证据。

### 测试

- 同 Chunk ID 合并；
- 完全相同正文不同 ID；
- 标题相同但正文互补；
- 同 Parent 相邻重叠 Child；
- 不同 Parent 的模板化重复；
- Warning 不被普通说明错误吞并；
- 去重顺序确定；
- Trace 不记录正文。

---

## Task 2.4：统一 Child Rerank 请求与预算

### Goal ID

`RAG-EV2-G204`

### 修改文件

- `rag/cloud/RerankCandidate.java`
- `rag/cloud/RerankRequestBudgeter.java`
- `rag/cloud/DashScopeRerankClient.java`
- `rag/ranking/RerankCoordinator.java`
- `rag/config/RagBudgetConfig.java`
- `rag/config/RagRetrievalConfig.java`
- 离线 `EvaluateRerankCommand`
- 离线 `OfflineHybridEvaluator`
- Rerank 诊断和人工审核 Writer
- 对应测试

### 实现要求

1. Rerank 只接收 Child。
2. 每个候选文本仅为：

   ```text
   sectionPath
   Child 正文
   ```

3. 不发送相邻 Child。
4. 不发送 Parent。
5. 默认 `top_n=15`，系统硬上限 20。
6. 使用共享 TokenEstimator 计算 6000 Token 初始预算。
7. 设置 7000 Token 语义硬保护。
8. 保留独立字符/字节传输硬保护。
9. 响应必须校验：
   - index 范围；
   - index 唯一；
   - score 有限；
   - 返回数量；
   - index → 原候选映射。
10. Rerank 失败时保留 RRF Child 顺序，标记 `RRF_FALLBACK`。

### 测试

- sectionPath + Child；
- 无 Parent/相邻 Child；
- Token 预算；
- 候选尾部剔除而非正文任意截断；
- 响应索引映射；
- 404/429/5xx/超时/取消；
- RRF 降级；
- Offline 与 Android Golden 一致。

---

## Task 2.5：实现 Child → Parent 聚合与完整 Parent Evidence

### Goal ID

`RAG-EV2-G205`

### 创建文件建议

- `rag/retrieval/ParentCandidate.java`
- `rag/retrieval/ParentCandidateAggregator.java`
- `rag/retrieval/ParentEvidenceAssembler.java`
- `rag/policy/ParentEvidenceBudgetPolicy.java`
- `rag/model/ParentRankingDiagnostics.java`

### 修改文件

- `rag/store/KnowledgeStoreGateway.java`
- `rag/store/ObjectBoxKnowledgeStoreGateway.java`
- `rag/retrieval/HybridRetrievalCoordinator.java`
- `rag/model/RetrievalEvidence.java`
- `rag/model/VehicleKnowledgeEvidence.java`
- `rag/model/RagResult.java`
- `rag/policy/EvidenceSelector.java`
- `rag/policy/EvidenceDeduplicator.java`
- `rag/policy/VehicleKnowledgeToolResultMapper.java`
- `rag/VehicleKnowledgeService.java`
- SourceLocator 与 Citation 相关测试（行为保持兼容）

### Store Gateway

新增只读批量方法：

```text
parentChunksByIds(List<String> parentIds)
```

要求：

- 只返回 `chunkLevel=PARENT`；
- 输入去重；
- 输出顺序不能依赖 ObjectBox；
- 调用方按 Parent 排名重新排序；
- 缺失 Parent fail closed，并产生稳定原因码；
- 不逐个 Parent 发起 N 次查询。

### Parent 聚合

1. 按 Rerank 后 Child 顺序遍历。
2. 以 `parentId` 聚合。
3. Parent 主分数取最高 Child Rerank Score。
4. RRF Fallback 时以最佳 Child Fusion Rank 代表 Parent。
5. 同分 Tie-break：
   - 最佳 Child RRF Rank；
   - Parent ID。
6. `supportingChildIds` 只保留内部诊断。
7. 同一 Parent 最终只出现一次。

### Parent Evidence

最终 `RetrievalEvidence` 的 `content` 必须是完整 Parent，而不是 Child。

内部诊断至少保留：

- parentId；
- bestChildId；
- supportingChildIds；
- bestChild Dense/BM25/RRF/Rerank Rank 与 Score；
- rankingSource；
- confidence features。

模型侧 `VehicleKnowledgeEvidence` 至少包含：

- evidenceId；
- Parent 完整 content；
- sectionPath；
- documentTitle；
- documentVersion；
- SourceLocator；
- applicability；
- retrievalConfidence。

不得暴露：

- parentId / childId；
- 原始 RRF/Rerank Score；
- 内部 Hash；
- ObjectBox ID。

### Parent Evidence 预算

1. 按 Parent 排名顺序选择。
2. 默认目标 3 个，最多 4 个。
3. 总 Token 上限初始 5000。
4. 按最终渲染文本计算：
   - sectionPath；
   - Parent 正文；
   - 引用必要字段。
5. 下一个完整 Parent超预算时停止加入。
6. 不截断 Parent。
7. 若第一名 Parent 单独就超过预算：
   - 不静默截断；
   - 返回受控失败或配置/构建诊断；
   - 追溯 Parent 分块硬上限失效原因。

### 测试

- 多 Child → 单 Parent；
- 多 Parent 排序；
- 最高 Child Score；
- RRF fallback；
- Parent 批量查询；
- Parent 缺失；
- 2～3 个常规结果；
- 最大 4；
- 预算停止；
- 不截断；
- Citation 仍指向正确 Parent Locator；
- ToolResult JSON 不泄露内部 ID/分数。

---

## Task 2.6：实现 Parent retrievalConfidence

### Goal ID

`RAG-EV2-G206`

### 创建文件建议

- `rag/model/RetrievalConfidence.java`
- `rag/model/RetrievalConfidenceFeatures.java`
- `rag/policy/ParentRetrievalConfidenceCalculator.java`
- `rag/config/RetrievalConfidenceConfig.java`

### 修改文件

- `RetrievalEvidence.java`
- `VehicleKnowledgeEvidence.java`
- `VehicleKnowledgeToolResultMapper.java`
- `RagTraceSnapshot.java`
- `RagTraceRecorder.java`
- ToolResult Schema 测试

### 实现要求

1. 置信度特征与等级分离。
2. 排序不依赖置信度等级，仍以最高 Child Score 排 Parent。
3. 在阈值未校准前输出 `UNASSESSED`。
4. RRF fallback 不伪造 Rerank 置信度。
5. 配置必须版本化。
6. Release Trace 不保存 Query 或正文。
7. 模型可见只输出等级，原始特征进入内部诊断。

### 暂不锁定

- HIGH/MEDIUM/LOW 的 Score 阈值；
- Margin 阈值；
- 降级模式是否允许 MEDIUM。

这些值由 Phase 3 Eval 校准后回填。

### Phase 2 测试门禁

离线：

```powershell
cd tools/rag-indexer
.\gradlew.bat clean test check installDist --console=plain
```

Android：

```powershell
cd ..\..
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Bundle：

1. 使用新输出目录构建 V2 `TEST_ONLY` Bundle；
2. 禁止覆盖 V4；
3. 执行独立 `verify`；
4. 记录：
   - Parent/Child 数；
   - Token 分布；
   - Embedding 成功数；
   - lexical term 数；
   - data.mdb Hash；
   - Manifest Chunk V2 配置；
   - `publishable=false`。

Phase 2 完成定义：

- V2 Bundle 可独立 Verify；
- Rerank 只处理 Child；
- 最终返回完整 Parent；
- Parent 去重和预算正确；
- confidence 在未校准时不伪造；
- 唯一下一 Goal 为 `RAG-EV2-G301`。

---

# Phase 3：Eval V2、真实样本与质量校准

## 目标

建立能够真实评价 Parent Evidence 是否足以回答问题的评测系统，并以新 Bundle 重新判断 RRF、Rerank、去重和置信度。

## Task 3.1：定义 Eval V2 Schema 与领域模型

### Goal ID

`RAG-EV2-G301`

### 创建文件建议

- `tools/rag-indexer/schemas/retrieval-evaluation-v2.schema.json`
- `evaluation/RetrievalEvaluationDatasetV2.java`
- `evaluation/RetrievalEvaluationLoaderV2.java`
- `evaluation/EvidenceSetExpectation.java`
- `evaluation/EvaluationAnswerability.java`
- `evaluation/EvaluationCategory.java`

### 保留文件

- Eval V1 Schema、Loader 和历史报告必须保留，只读历史资产仍可加载。

### V2 Case 建议结构

```json
{
  "caseId": "diy-roof-rack-installation",
  "query": "如何安装车顶行李架？",
  "category": "DIY_OPERATION",
  "answerability": "ANSWERABLE",
  "answerCriteria": [
    "包含安装位置",
    "包含关键步骤",
    "包含安全限制"
  ],
  "acceptableEvidenceSets": [
    {
      "requiredParentIds": ["parent-a"],
      "optionalLocatorChildIds": ["child-a-1"],
      "rationale": "该 Parent 独立包含完整安装步骤和限制"
    },
    {
      "requiredParentIds": ["parent-b", "parent-c"],
      "optionalLocatorChildIds": [],
      "rationale": "两个 Parent 联合覆盖步骤与警告"
    }
  ],
  "tags": ["HTML", "口语化"],
  "split": "TEST"
}
```

### 语义

- `acceptableEvidenceSets` 之间是 OR；
- 一个集合内 `requiredParentIds` 是 AND；
- `optionalLocatorChildIds` 仅用于 Child 定位诊断，不是主成功条件；
- `NO_EVIDENCE` 用例的 Evidence Set 必须为空；
- `answerCriteria` 用于人工核验答案充分性；
- Query 只保存在本地 Eval 输入，不进入可提交公开报告；
- 报告保存 Query Hash。

### 校验

- 未知字段拒绝；
- caseId、Query 不重复；
- Parent ID 必须存在；
- Child ID 若提供必须存在且指向期望 Parent；
- ANSWERABLE 至少一个 Evidence Set；
- NO_EVIDENCE 禁止期望 Parent；
- split 只能使用受控枚举；
- rationale 不为空。

---

## Task 3.2：实现 Parent Evidence 主指标与诊断指标

### Goal ID

`RAG-EV2-G302`

### 创建/修改文件

- `evaluation/OfflineHybridEvaluator.java`
- `evaluation/RetrievalMetrics.java` 或新增 `RetrievalMetricsV2.java`
- `evaluation/RetrievalEvaluationReportWriter.java` 或新增 V2 Writer
- `evaluation/RerankDiagnosticReportWriter.java`
- `evaluation/RerankManualReviewReportWriter.java`
- CLI `evaluate`、`evaluate-rerank` 路由
- 对应测试

### 主指标

- `ParentEvidenceCoverage@1`
- `ParentEvidenceCoverage@2`
- `ParentEvidenceCoverage@3`
- `ParentEvidenceCoverage@4`
- `ParentEvidenceMRR`
- `NoEvidenceAccuracy`

一个 Case 成功的条件：

```text
Top-K Parent 集合完整覆盖至少一个 acceptableEvidenceSet.requiredParentIds
```

### 诊断指标

- Dense Child Recall@K；
- BM25 Child Recall@K；
- RRF Child Recall@K；
- Rerank Child Recall@K；
- Child → Parent 映射成功率；
- Exact Duplicate Drop Count；
- Near Duplicate Drop Count；
- Parent 聚合前/后候选数量；
- 每个最终 Parent 的支持 Child 数；
- Parent Evidence Token 使用量；
- Rerank/RRF 模式；
- 分类别、格式、单 Parent/多 Parent的指标；
- 延迟 p50/p95。

### 报告隐私

提交报告只保存：

- caseId；
- Query Hash；
- Parent/Child ID；
- Rank、Score、计数；
- Confidence；
- Hash；
- 原因码。

正文、Query、人工备注只进入本地忽略的 HTML/JSON 审核文件。

---

## Task 3.3：重建至少 50 条高质量真实评测集

### Goal ID

`RAG-EV2-G303`

### 创建文件

- `tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`
- 本地工作目录：
  - `work/eval-v2-authoring.html`
  - `work/eval-v2-review.json`
- 可提交的脱敏评测说明：
  - `docs/testresult/rag/parent_evidence_eval_v2_dataset_review.md`

### 样本要求

首版至少 50 条，覆盖：

- 车辆功能使用；
- 维护保养；
- 充电；
- 安全限制；
- DIY 操作；
- 保修；
- 服务预约；
- 服务中心；
- 表格或参数；
- 口语化 Query；
- 同义表达；
- 多 Parent 联合证据；
- NO_EVIDENCE；
- 容易被近重复内容干扰的问题。

### 标注流程

1. 基于 V2 Bundle 的 Parent 审核页选问题。
2. 问题必须像真实用户提问，禁止为了命中某个 ID 反向拼接标题。
3. 人工阅读完整 Parent。
4. 填写 answerCriteria。
5. 标记一个或多个可接受 Evidence Set。
6. 标记可选定位 Child。
7. 第二次人工复核问题是否有意义、证据是否完整。
8. 将样本划分为：
   - DEV：用于阈值和参数校准；
   - TEST：只用于最终验证。
9. 禁止使用 TEST 结果反复调参。

### 旧 20 条处理

- 不自动迁移 ID；
- 可将 Query 作为候选素材；
- 每条必须重新判断问题价值；
- 标注错误或证据不足的问题删除或重写；
- 历史人工审核结果作为筛选依据。

### 人工 Gate

项目负责人审核：

- 问题质量；
- Evidence Set；
- 多 Parent AND/OR 语义；
- NO_EVIDENCE；
- 至少 50 条数量与类别分布。

---

## Task 3.4：执行 RRF、Rerank、去重消融与置信度校准

### Goal ID

`RAG-EV2-G304`

### 测试矩阵

在同一 V2 Bundle、同一 Eval DEV 集上至少运行：

1. Dense only；
2. BM25 only；
3. Dense + BM25 + RRF；
4. RRF + Exact Dedup；
5. RRF + Exact/Near Dedup；
6. RRF + Dedup + Rerank；
7. Rerank 失败 → RRF fallback。

每种模式只改变一个变量，禁止同时改 Chunk、HNSW、Top-K 和相似阈值后归因于单一因素。

### 需要校准

- Dense/BM25 选择 20 或 30；
- Fusion Candidate 上限；
- Near Duplicate 阈值；
- Rerank 默认 15、最大 20；
- Rerank 6000 Token 预算；
- Parent 5000 Token 预算；
- Confidence HIGH/MEDIUM/LOW 阈值；
- No-Evidence 阈值。

### Confidence 校准

对每个最终 Parent 保存：

- bestChildRerankScore；
- marginToNextParent；
- rankingSource；
- 是否降级；
- 人工 Evidence 正确性。

按 DEV 集选择阈值，要求：

- HIGH 的错误率可解释并受控；
- 不能只追求覆盖率而把全部结果标 HIGH；
- RRF fallback 不与正常 Rerank 混用同一阈值；
- 阈值和样本分布写入报告；
- 最终只在 TEST 集运行一次确认。

### 人工 Rerank 审核

对 TEST 中所有新失分样本生成 HTML，展示：

- Query；
- 期望 Parent；
- 最终 Parent；
- 支持 Child；
- RRF/Rerank Rank；
- 去重记录；
- 完整 Parent 正文；
- 人工四类结论。

### Phase 3 测试门禁

```powershell
cd tools/rag-indexer
.\gradlew.bat clean test check installDist --console=plain
```

并生成不可覆盖的 V2 报告。Phase 3 完成定义：

- 至少 50 条人工复核样本；
- 主指标为 Parent Evidence；
- RRF/Rerank 结论可解释；
- Confidence 阈值有 DEV 证据；
- TEST 集没有参与调参；
- 唯一下一 Goal 为 `RAG-EV2-G401`。

---

# Phase 4：跨端验收、AVD 与文档收口

## 目标

证明 CLI 构建的 V2 Bundle 能被 Android 运行时正确消费，并完成 Parent Evidence、置信度、引用和回归验证。

## Task 4.1：建立跨端 V2 Policy Golden

### Goal ID

`RAG-EV2-G401`

### 创建文件建议

- `rag-schema/test-vectors/parent-child-retrieval-v2.json`
- CLI 对应 Golden Test
- Android 对应 Golden Test

### Golden 至少覆盖

- Fusion Candidate 顺序；
- Exact/Near Dedup；
- Rerank index 映射；
- Child → Parent；
- Parent Score；
- Tie-break；
- Parent 预算；
- Confidence features；
- RRF fallback。

CLI 与 Android 不必共享实现类，但必须消费同一 Golden 并得到一致结果。

---

## Task 4.2：Android JVM 全量回归

### Goal ID

`RAG-EV2-G402`

### 必跑测试

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

### 重点验证

- Store Manifest V2；
- Parent 批量读取；
- Child Dense/BM25；
- RRF 和去重；
- Child Rerank；
- RRF fallback；
- Parent 聚合；
- Parent Evidence 预算；
- confidence；
- ToolResult JSON；
- CitationGuard；
- KnowledgeTurnBuffer 紧凑投影；
- Trace 脱敏；
- 取消、超时和错误映射。

---

## Task 4.3：Automotive AVD 跨端验证

### Goal ID

`RAG-EV2-G403`

### 输入

- 新 V2 TEST_ONLY Bundle；
- 仅放入 `androidTest` 资产；
- 不进入 main APK。

### 验证

1. Asset 安装；
2. Manifest/Hash/Schema/Chunk V2 配置；
3. ObjectBox 打开；
4. Parent 与 Child 数量；
5. Child → Parent 完整性；
6. Dense 查询；
7. BM25 查询；
8. Parent 批量读取；
9. 完整 Parent Evidence；
10. Evidence Token 预算；
11. Citation；
12. Scope/Metadata；
13. Store 关闭和切换。

### 命令

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

若 AVD 不可用：

- 记录环境错误和用户需要执行的具体步骤；
- 不将未执行写成通过；
- JVM Gate 可完成，但设备 Gate 保持未完成。

---

## Task 4.4：更新状态、测试报告与维护文档

### Goal ID

`RAG-EV2-G404`

### 修改/创建文件

- `docs/plan_overall/rag/rag_execution_status.md`
- `docs/testresult/rag/parent_child_chunk_v2_report.md`
- `docs/testresult/rag/parent_evidence_eval_v2_report.md`
- `docs/testresult/rag/android_parent_evidence_v2_report.md`
- `tools/rag-indexer/README.md`
- 根 `README.md` 中 RAG 现状章节（仅在实现完成后）

### 必须记录

- V2 参数；
- Parent/Child 分布；
- Bundle Hash；
- Eval 数据集版本；
- RRF/Rerank 指标；
- 去重效果；
- Confidence 阈值；
- Android JVM/AVD 结果；
- 未执行项；
- 目标 arm64-v8a 风险；
- 是否仍为 TEST_ONLY；
- 是否允许进入下一发布 Goal。

### Phase 4 完成定义

- 离线与 Android Golden 一致；
- JVM、构建和 Lint 通过；
- AVD V2 Bundle 验证通过或明确记录外部阻塞；
- 文档与代码现状一致；
- 未经人工批准不提升 `APPROVED`。

---

## 8. 文件级改造总表

| 区域 | 主要文件 | 计划职责 |
|---|---|---|
| 上位协议 | `vehicle_agent_rag_design.md` | 固化新标准链路 |
| 共享契约 | `rag-schema/.../RagTokenEstimator*` | Token 跨端一致 |
| Build Schema | `rag-build*.schema.json`、`ConfigLoader` | V2 Chunk 参数 |
| Parser Model | `StructuredBlock`、`BlockStructure` | 标题层级、列表/步骤组 |
| PDF | `PdfHeadingDetector/LevelResolver/PathTracker` | 恢复 PDF sectionPath |
| HTML | `HtmlStructureWalker` | Heading/List 结构事实 |
| Markdown | `MarkdownDocumentParser` | Heading/List 结构事实 |
| Parent | `DocumentSectionTreeBuilder`、`SectionParentChunker` | 小节级 Parent |
| Child | `SemanticChildSplitter`、`LengthFallbackSplitter` | 语义 Child 与兜底 overlap |
| Stable ID | `StableIdGenerator` | V2 Parent/Child ID |
| Embedding | `EmbeddingTextRenderer` | sectionPath + Child |
| BM25 | `LexicalDocumentRenderer`、`LexicalIndexBuilder` | sectionPath + Child |
| Manifest | CLI/Android Manifest 类与 Schema | Chunk V2 兼容 |
| Candidate | `FusionCandidateDeduplicator` | 完全/高度相似去重 |
| Rerank | Client/Budgeter/Coordinator | Child-only Rerank |
| Store | `KnowledgeStoreGateway` | Parent 批量读取 |
| Parent 聚合 | `ParentCandidateAggregator` | 最高 Child Score |
| Evidence | `ParentEvidenceAssembler/BudgetPolicy` | 完整 Parent |
| Confidence | `ParentRetrievalConfidenceCalculator` | 等级与诊断 |
| ToolResult | `VehicleKnowledgeEvidence/Mapper` | Parent + confidence |
| Eval Schema | `retrieval-evaluation-v2.schema.json` | Parent Evidence Set |
| Eval Engine | `OfflineHybridEvaluator`、Metrics/Writer | Parent 主指标 |
| 人工审核 | Chunk/Eval/Rerank HTML Writer | 本地正文审阅 |
| Trace | `RagTrace*` | 无正文诊断 |

---

## 9. 关键实现不变量

1. Parent 不参与 Dense/BM25 默认召回。
2. Rerank 的对象始终是 Child。
3. Rerank 输入不包含 Parent 或相邻 Child。
4. sectionPath 进入 Dense、BM25、Rerank。
5. 最终 Evidence 是完整 Parent。
6. Parent 不允许被 Evidence Budget 截断。
7. 同一 parentId 最终只返回一次。
8. Parent 排名取最高 Child Rerank Score。
9. RRF fallback 有独立排名来源。
10. retrievalConfidence 不是答案正确概率。
11. 未校准时不伪造 HIGH/MEDIUM/LOW。
12. overlap 默认 0，仅长度兜底启用。
13. overlap 不跨 Parent。
14. 不切断完整语义原子组。
15. ObjectBox Schema 默认不变。
16. Chunk V2 必须重建全部 Embedding 和 BM25。
17. 旧 Bundle、旧 Eval 和审核产物不得覆盖。
18. 正文和 Query 不进入可提交 Trace/评测报告。
19. Eval 主指标基于 Parent Evidence Set。
20. TEST_ONLY 未经人工批准不得提升为 APPROVED。

---

## 10. 最终 Definition of Done

只有同时满足以下条件，本计划才可标记完成：

- [ ] RAG 总体设计已修订，无旧 Parent Context 冲突；
- [ ] TokenEstimator V2 跨端 Golden 一致；
- [ ] V1/V2 配置兼容行为有测试；
- [ ] PDF/HTML/Markdown 均能提供可用 sectionPath；
- [ ] Parent 是最小自然完整小节；
- [ ] Child 使用语义优先、默认不重叠策略；
- [ ] 兜底 overlap 可审计且不跨 Parent；
- [ ] Chunk 审核页经项目负责人确认；
- [ ] V2 Stable ID、Embedding、BM25、ObjectBox Bundle 已重建；
- [ ] V2 Bundle 独立 Verify 成功；
- [ ] RRF 后 Exact/Near Dedup 有测试和诊断；
- [ ] Rerank 只处理 Child；
- [ ] Child 正确映射 Parent；
- [ ] Parent 以最高 Child Score 排序；
- [ ] 最终 Evidence 为完整 Parent；
- [ ] Parent Evidence 不被截断；
- [ ] 最终 Parent 通常 2～3、最多 4；
- [ ] retrievalConfidence 语义正确；
- [ ] Eval V2 至少 50 条且经过人工审核；
- [ ] 主指标为 Parent Evidence Coverage；
- [ ] RRF/Rerank 结论有消融证据；
- [ ] Confidence 阈值由 DEV 校准、TEST 验证；
- [ ] CLI 全量测试通过；
- [ ] Android JVM、assemble、lint 通过；
- [ ] AVD Gate 通过或明确记录真实外部阻塞；
- [ ] 所有报告、台账、README 与实现一致；
- [ ] Bundle 仍保持 TEST_ONLY，除非项目负责人另行书面批准。

---

## 11. 子 Agent 的第一个执行动作

子 Agent 获取本计划后，必须从 `RAG-EV2-G001` 开始，不得直接进入 Chunk 编码。

---

## 12. 最终 DoD 审计记录（2026-07-27）

本节是对当前工作区实际产物和命令结果的最终审计，不修改历史 Goal 记录。

| Gate | 审计结论 | 证据 |
|---|---|---|
| Chunk/Parent/Child | 通过 | `parent_child_chunk_v2_report.md`、Chunk 审核产物、V2 Manifest/Bundle |
| Dense/BM25/RRF/Dedup/Rerank/Evidence | 通过 | `parent_evidence_ablation_v2.md`、`evaluation_parent_evidence_v2_ablation_v2.json` |
| Confidence | 通过（当前先跑通口径） | `parent_evidence_confidence_calibration_v2.md`；DEV 选择、TEST 一次确认、Android `RetrievalConfidencePolicy` |
| Bundle/Schema/Android | 通过 | Manifest/Store Verify；Android JVM 454 tests；APK/Lint；Automotive AVD 7/7 |
| Eval 样本规模 | 批准例外 | 当前 33 条（DEV 9、TEST 24），项目负责人批准作为先跑通验收集；正式 50 条质量集延期，不伪装为已完成 |
| No-Evidence 阈值 | 不适用例外 | 当前数据集没有 `NO_EVIDENCE` 样本；保留受控默认策略，不声称有经验校准结论 |
| 发布 | 按计划保留 | Bundle 继续 `TEST_ONLY`，未复制到 `app/src/main/assets`，未提升 `APPROVED` |

在上述批准例外范围内，08 计划的实现与分阶段验证目标已完成；正式质量扩展和发布审批不属于本次“先跑通”交付。

首轮只执行：

1. `git status --short`；
2. 读取本计划、总体设计和执行台账；
3. 核对当前未提交 Rerank 诊断改动；
4. 记录历史 V4/Eval/审核资产；
5. 更新执行台账；
6. 汇报发现的规范冲突或工作区风险。

完成 G001 并给出真实验证证据后，才能进入 G002。
