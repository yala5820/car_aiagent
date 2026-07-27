# RAG PDF 行 Block、Parent Paragraph 与 Child 重划分修订计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在保留 PDF 视觉行 Block 的前提下，修复两列阅读顺序，新增 Parent 内 Paragraph Reconstruction，并按结构、尺寸和 Paragraph Embedding 重新生成 Child。

**Architecture:** PDF Parser 只输出带坐标和列信息的视觉行；Parent 阶段按同一小节、同一列和行距/缩进恢复 Paragraph；PDF 目录页作为标题层级参考但不进入最终 Evidence；Paragraph Embedding 只用于达到阈值的普通短 Paragraph；Child 继续遵守小 Paragraph 强制吸附、语义合并上限和 512 硬上限。Parent overlap 只用于同一超大小节被拆成多个 Parent 的情况，并按完整 Paragraph 复制约 10%。

**Tech Stack:** Java 17、Apache PDFBox 2.0.37、ObjectBox 5.4.0、DashScope `text-embedding-v4`、现有离线 RAG Indexer、JUnit 5。

---

## 1. 与旧计划的关系

旧计划 [08-rag-parent-child-retrieval-eval-v2-implementation-plan.md](./08-rag-parent-child-retrieval-eval-v2-implementation-plan.md) 继续作为 RAG V2 总计划，Phase 0 的协议、TokenEstimator、Eval V2 和后续检索链路仍然有效。

本修订计划只覆盖旧计划 Phase 1 的分块实现部分：

- 旧的“Parser 阶段恢复完整句子 Block”被本计划覆盖；
- 旧的 `SemanticChildSplitter` 直接消费 Block 的逻辑被本计划覆盖；
- 旧计划 Phase 1 的人工审核 Gate 需要在本计划完成后重新执行；
- 本计划完成并通过人工 Chunk 审核后，才允许继续旧计划 Phase 2（重新构建 Embedding、BM25、Bundle 和 Parent Evidence 链路）。

不得在本计划完成前执行旧计划 Phase 2，也不得使用旧 Chunk ID、旧 Embedding 或旧 Bundle 继续评测。

## 2. 固定规则

### 2.1 PDF Block

- 一个视觉行仍然是一个 `StructuredBlock`；不得在 Parser 阶段按句号重新切 Block。
- Block 必须保留 `BoundingBox`、页码、字号、页面行序和列序。
- 两列页面阅读顺序固定为：左列从上到下，再右列从上到下。
- 正文 Paragraph 重建不得跨列。
- 标题、页眉、页脚、列表、Warning、表格和跨栏内容必须拥有明确结构标记。

### 2.2 Paragraph

- Paragraph 只在 Parent 内构建，不进入通用 Parser Block 语义。
- Paragraph 重建只合并同一 Parent、同一列、满足行距/缩进/连续性规则的视觉行。
- 不跨标题、Warning、列表组、表格、列或不连续页合并。
- Paragraph 需要保留起止行、页码、列、BoundingBox 和稳定来源定位。

### 2.3 Child

| 参数 | 值 |
|---|---:|
| 建议范围 | 160～320 tokens |
| 目标值 | 256 tokens |
| 语义合并上限 | 384 tokens |
| 硬上限 | 512 tokens |
| Paragraph 语义阈值 | cosine `> 0.7` |
| 默认 overlap | 0 |
| 强制吸附阈值 | `<30 tokens` |
| 无 Embedding 直接合并阈值 | `30～100 tokens` |
| Embedding 判断起始阈值 | `>100 tokens` |

- Warning、列表、步骤、表格和其他原子语义组直接单独作为 Child。
- 仅普通 Paragraph 参与以下小 Paragraph 规则；结构原子组不得被普通 Paragraph 跨边界吸收。
- `<30 tokens` 的普通 Paragraph 必须优先吸附到前一个 Child；前方无法在 512 硬上限内容纳时，改为吸附到后一个 Child。
- `30～100 tokens` 的普通 Paragraph 不调用 Embedding，直接尝试与相邻普通 Child 合并；普通合并优先遵守 384 soft 上限。
- `100～159 tokens` 的普通 Paragraph 才调用 Embedding 判断是否合并；`160 tokens` 以上不再请求 Embedding，按独立 Child 处理。
- Paragraph 在 160～320 tokens 时直接作为单独 Child。
- 只有短 Paragraph 才触发语义合并。
- 允许合并两个或多个短 Paragraph，但合并后不得超过 384 tokens。
- 多 Paragraph 合并同时满足：当前 Child 语义中心与新 Paragraph cosine `> 0.7`，且前一个 Paragraph 与新 Paragraph cosine `> 0.7`。
- 单个 Paragraph 超过 512 tokens 时，按完整句子、次级标点、长度顺序兜底拆分。

### 2.4 Parent overlap

- 普通 Parent 不重叠。
- 只有同一个原始小节因超过 Parent 硬上限而拆成多个 Parent 时启用 overlap。
- overlap 目标约为 Parent 内容的 10%，按完整 Paragraph 向上取整。
- 不切断 Paragraph，不跨兄弟小节，不跨 PDF 列。
- 重叠 Paragraph 在不同 Parent 中拥有不同 Parent/Child 稳定 ID；评测报告必须能识别这是边界复制，不得把它误报为 Parser 重复。

### 2.5 PDF 目录辅助标题层级

- 自动识别包含目录标题、点线和页码的 PDF 页面；当前 Model Y 资料的物理第 3、4 页作为已知验证样本。
- 目录条目解析为 `title`、显式编号、层级、目录页码和原始定位，作为正文标题层级参考。
- 目录页不进入最终正文 Block、Parent、Child 或 Evidence，但解析结果必须保留在 Parser 审核报告中。
- 正文标题优先按目录条目与页码范围匹配；未匹配标题继续使用字号、间距、缩进、全宽和序列一致性规则。
- 目录页码与 PDF 物理页码不一致时必须计算并校验页码偏移；无法确认偏移时不得强行覆盖正文层级。
- 目录只能校正标题路径和层级，不得替代正文内容，也不得把目录条目直接当作正文 Evidence。

## 3. Phase A：协议和数据结构

### Task A1：冻结旧 Phase 1 并版本化新分块策略

**Files:**

- Modify: `tools/rag-indexer/corpus/model_y_2026_refresh_trial/rag-build-v2-review.json`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ChunkingConfig.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ConfigValidator.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ConfigLoader.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/artifact/ManifestBuilder.java`
- Modify: `rag-schema/contracts/knowledge-bundle-manifest.schema.json`
- Modify: `docs/plan_overall/rag/rag_execution_status.md`

- [ ] 将 `semanticStrategyVersion` 升级为新的版本号，并增加 `paragraphReconstructionVersion`、`pdfColumnLayoutVersion`、`paragraphCosineThreshold=0.7`、`childMergeMaxTokens=384`、`parentSplitOverlapRatio=0.10`。
- [ ] 将 Child 建议下限调整为 160，Child 软合并上限调整为 384，硬上限保留 512。
- [ ] 配置校验拒绝负值、阈值不在 `0 < threshold <= 1`、overlap 比例不在 `0 <= ratio <= 0.2` 的配置。
- [ ] 在台账中记录旧 Phase 1 结果不再作为新策略基线，旧 Chunk/Embedding/Bundle 必须重新生成。
- [ ] 增加配置加载和指纹测试，确保新策略变化会使 Build Fingerprint 变化。
- [ ] Manifest 和共享 Schema 在新 Bundle 阶段记录同一组策略字段；本任务不修改 ObjectBox Entity 或 Meta Model UID。

## 4. Phase B：PDF 视觉行和两列阅读顺序

### Task B1：建立页面列模型和行级布局元数据

**Files:**

- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfColumnLayout.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/model/PdfLineMetadata.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/model/StructuredBlock.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/model/BlockStructure.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfGlyphExtractor.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfPageLayout.java`

- [ ] 为 PDF 行保存 `pageNumber`、`columnIndex`、`lineIndex`、`fullWidth` 和 BoundingBox；非 PDF Block 使用兼容默认值。
- [ ] 页面模型保留页面宽度，允许判断跨栏标题和全宽内容。
- [ ] `columnIndex=0` 表示左列，`columnIndex=1` 表示右列，`columnIndex=-1` 表示全宽或无法可靠归类。
- [ ] 保留旧构造函数，避免 HTML/Markdown Fixture 和共享 Schema 被迫增加 PDF 专用参数。

### Task B2：修复两列阅读顺序并保持行级 Block

**Files:**

- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfReadingOrderResolver.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfBlockAssembler.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/SemanticBlockNormalizer.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/DocumentParsingPipeline.java`

- [ ] 页面内根据行左边界、行宽和页面宽度构建最多两个正文列；不再依赖全页单一最大间隔作为唯一分栏依据。
- [ ] 输出顺序严格为左列 top-to-bottom，再右列 top-to-bottom；全宽标题和跨栏内容单独输出。
- [ ] PDF 进入 Pipeline 时只做清洗、结构标记和来源归一化，不做句子级 Block 合并。
- [ ] 取消 PDF 的无条件跨页行合并；跨页连续性只留给 Paragraph Reconstruction 阶段处理。
- [ ] 标题候选必须同时满足标题类型和可靠层级；`headingLevel=0` 的正文候选不得被错误标记成不可合并的标题。

### Task B3：两列和行级 Parser 测试

**Files:**

- Create/Modify: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfReadingOrderResolverTest.java`
- Create/Modify: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfBlockAssemblerTest.java`
- Modify: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/parser/SemanticBlockNormalizerTest.java`

- [ ] 验证左列全部行先于右列行。
- [ ] 验证两列内容不会交叉拼接。
- [ ] 验证全宽标题不会成为左右列正文的一部分。
- [ ] 验证 PDF Block 保持视觉行粒度，不因句号自动拆分或合并。
- [ ] 验证页眉页脚仍被排除，原始来源定位不丢失。

## 5. Phase C：Parent Paragraph Reconstruction

### Task C1：创建 Paragraph 中间模型和重建策略

**Files:**

- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ReconstructedParagraph.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphReconstructionPolicy.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphReconstructor.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ParentChunk.java`

- [ ] Paragraph 记录完整文本、来源 Block、列、首末行、Token、BoundingBox、结构类型和定位范围。
- [ ] 对同一 Parent 按 `columnIndex` 分组，再按 page/top/lineIndex 排序。
- [ ] 使用同列连续行、相对行距、左边界/缩进、右边界和页面连续性判断是否属于同一 Paragraph。
- [ ] 新标题、Warning、列表组、表格、全宽内容和列变化强制结束 Paragraph。
- [ ] CJK 行拼接不插入空格；Latin/数字相邻时按现有规则补空格。
- [ ] 跨页只允许同列、同 Parent、未结束视觉段落的连续行恢复。

### Task C2：调整 Parent 划分和 10% Paragraph overlap

**Files:**

- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/SectionParentChunker.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/DocumentSectionTreeBuilder.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/DocumentChunker.java`

- [ ] 先按最小自然小节得到 Parent Draft，再执行 Paragraph Reconstruction。
- [ ] 普通小节保持一个 Parent，不因为 Paragraph 短而强行拆分。
- [ ] 超过 Parent 硬上限时只在 Paragraph 边界拆分。
- [ ] 同一超大小节产生多个 Parent 时，向相邻 Parent 复制完整 Paragraph，目标约 10%。
- [ ] 不对普通小节、兄弟小节、不同列和不同结构组引入 overlap。
- [ ] Parent 稳定 ID、Parent segment index 和 overlap 诊断必须可追溯。

### Task C3：Paragraph Reconstruction 测试

**Files:**

- Create: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphReconstructorTest.java`
- Create: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/chunk/ParentParagraphOverlapTest.java`

- [ ] 验证同列、连续行合并为 Paragraph。
- [ ] 验证行距明显增大时切换 Paragraph。
- [ ] 验证首行缩进和左右边界变化可以形成新 Paragraph。
- [ ] 验证左右列永不合并。
- [ ] 验证跨页同列连续段落可以恢复，非连续页不合并。
- [ ] 验证 Parent overlap 按完整 Paragraph 复制，约为 10%，不产生半 Paragraph。

## 6. Phase D：基于 Paragraph Embedding 的 Child

### Task D1：增加 Paragraph Embedding 预处理阶段

**Files:**

- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/embedding/ParagraphEmbeddingRequest.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/embedding/ParagraphEmbeddingCoordinator.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/embedding/EmbeddingCacheKey.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/pipeline/DocumentBuildState.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/pipeline/DocumentBuildStageRunner.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/pipeline/BuildComponentFactory.java`

- [ ] 使用现有 `DashScopeDocumentEmbeddingClient` 和 `text-embedding-v4`，不新增模型或 LLM。
- [ ] 只为触发语义合并的短 Paragraph 请求 Embedding；Warning、适中 Paragraph 和不可能合并的超长 Paragraph 不请求。
- [ ] 缓存键包含文档 Hash、Parent、Paragraph 文本 Hash、Embedding 模型、维度和策略版本。
- [ ] API Key 不进入请求日志、异常、缓存或报告。
- [ ] Embedding 失败时按计划规定：构建进入受控失败或使用显式 deterministic fallback，不得产生部分 Child 并继续发布。
- [ ] 保持外部 Build Phase 顺序兼容；Paragraph Embedding 作为 `CHUNKED` 阶段内部的可恢复子阶段，避免旧 Bundle 被误认为同一策略。

内部执行顺序固定为：

```text
Parser 行 Block
  -> Parent Draft
  -> Parent Paragraph Reconstruction
  -> 收集短 Paragraph Embedding 请求
  -> 读取/写入 Paragraph Embedding Cache
  -> ParagraphChildPlanner
  -> 最终 Child
```

`DocumentChunker` 不得先生成最终 Child 再反过来请求 Paragraph Embedding。Paragraph Embedding 调用失败时，正式构建必须 fail-closed；仅允许显式的 Parser/Chunk 预览命令使用 deterministic fallback，并在报告中记录 `SEMANTIC_EMBEDDING_FALLBACK`，不得把 fallback 结果当成正式 Bundle。

### Task D2：重写 Child 合并器

**Files:**

- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphChildPlanner.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/SemanticChildSplitter.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ChunkBoundaryPolicy.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ChildChunk.java`

- [ ] 先处理结构原子组，直接生成独立 Child。
- [ ] 160～320 tokens 的普通 Paragraph 直接生成独立 Child。
- [ ] 320～384 tokens 的普通 Paragraph 也独立生成 Child，不再尝试继续吸收后续 Paragraph。
- [ ] 只有短 Paragraph 进入语义合并候选。
- [ ] 使用当前 Child Paragraph 向量归一化平均值和相邻 Paragraph cosine；两者都严格 `> 0.7` 才继续合并。
- [ ] 合并后超过 384 tokens 时结束当前 Child，不继续请求新的语义判断。
- [ ] 不跨 Parent、不跨列、不跨结构边界合并。
- [ ] 单 Paragraph 超过 512 tokens 时按完整句子、次级标点、长度顺序兜底，保留诊断。
- [ ] `splitReason` 增加 `PARAGRAPH_SINGLETON`、`PARAGRAPH_SEMANTIC_MERGE`、`STRUCTURAL_ATOMIC`、`LENGTH_FALLBACK`，便于审核。

### Task D3：Child 测试和离线真实语料验证

**Files:**

- Create: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphChildPlannerTest.java`
- Modify: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/chunk/DocumentChunkerTest.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/report/ChunkQualityAnalyzer.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/report/ChunkReviewHtmlWriter.java`

- [ ] 覆盖 Warning 独立 Child、160～320 Paragraph 独立 Child、短 Paragraph 多段合并、cosine 等于 0.7 时不合并、cosine 大于 0.7 时合并。
- [ ] 覆盖合并超过 384、硬上限 512、跨 Parent/跨列禁止合并。
- [ ] 质量报告展示 Paragraph 数量、Child 组成 Paragraph 数、语义合并分数、Parent overlap 和重复边界来源。
- [ ] 使用 Model Y 全量 Corpus 重新执行 `validate`，不得覆盖旧 run。
- [ ] 人工审核新的 `chunk-review.html`，重点检查两列顺序、Paragraph 边界、Warning 独立性、Child 主题连续性和 Parent overlap。

## 7. Phase Gate 和后续顺序

本修订计划的完成条件：

1. 两列 PDF 读取顺序测试通过，未发现左右列交叉；
2. PDF Block 保持视觉行粒度；
3. Paragraph Reconstruction 测试通过；
4. Child 规则测试通过；
5. Model Y 全量 `validate` 通过；
6. 人工审核新的 Parser/Chunk 页面通过；
7. 新配置和策略版本写入报告与台账。

只有以上条件全部满足，才恢复旧计划的执行顺序：

```text
本修订计划完成
  -> 旧计划 Phase 1 人工 Gate 关闭
  -> 旧计划 Phase 2：重新 Embedding/BM25/Bundle
  -> 旧计划 Phase 3：Eval V2
  -> 旧计划 Phase 4：跨端验收
```

本计划不授权：

- 修改 ObjectBox Meta Model；
- 将测试 Bundle 提升为 `APPROVED`；
- 复制 TEST_ONLY Bundle 到主 APK；
- 使用旧 Child ID 或旧 Embedding 继续评测；
- 把 Embedding 相似度解释为答案正确概率。

## 8. Phase E：目录辅助标题校正与小 Paragraph 重划分（本轮新增）

本阶段建立在 Phase A-D 已完成的实现之上。它会使所有 Parent、Child、Embedding 和评测输入失效，必须生成新的独立验证 run，不得覆盖 `e2979946-751a-4cf0-8c13-f7c040e2b373` 或其他历史产物。

### Task E1：版本化小 Paragraph 规则

**Files:**

- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ChunkingConfig.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ConfigLoader.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/config/ConfigValidator.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/artifact/ManifestBuilder.java`
- Modify: `rag-schema/contracts/knowledge-bundle-manifest.schema.json`
- Modify: `tools/rag-indexer/corpus/model_y_2026_refresh_trial/rag-build-v2-review.json`

- [x] 增加 `childForceMergeMaxTokens=30`、`childDirectMergeMaxTokens=100` 和 `pdfTocReferenceVersion`。
- [x] 将 `semanticStrategyVersion` 升级，确保旧 Chunk、Embedding 和 Bundle 不会被误认为同一策略。
- [x] 校验阈值关系：`0 < forceMerge < directMerge < childIdealMin <= childSoftMax < childHardMax`。
- [x] Manifest、配置指纹和质量报告必须同时记录这些字段。

### Task E2：实现小 Paragraph 合并策略

**Files:**

- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ChunkBoundaryPolicy.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphChildPlanner.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/report/ChunkQualityAnalyzer.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/report/ChunkReviewHtmlWriter.java`

- [x] `<30 tokens` 普通 Paragraph 先尝试吸附前方 Child，超过 512 时再尝试后方 Child；前后均不可容纳时保留并记录诊断。
- [x] `30～100 tokens` 普通 Paragraph 不请求 Embedding，按相邻顺序尝试合并，优先不超过 384。
- [x] `100～159 tokens` 才进入 Embedding cosine 判断；`>=160 tokens` 独立成为 Child。
- [x] 不跨 Parent、列、标题、Warning、列表、步骤和表格等结构边界。
- [x] 增加 `FORCED_SMALL_PARAGRAPH_MERGE`、`DIRECT_SMALL_PARAGRAPH_MERGE`、`SMALL_PARAGRAPH_UNMERGED` 诊断/拆分原因及统计。

### Task E3：解析目录并校正正文标题层级

**Files:**

- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfTocEntry.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfTocReference.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfTocReferenceExtractor.java`
- Create: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfHeadingHierarchyNormalizer.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfDocumentParser.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfBlockAssembler.java`
- Modify: `tools/rag-indexer/src/main/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfHeadingLevelResolver.java`

- [x] 识别目录页并解析标题文本、编号、层级、显示页码和来源定位。
- [x] 计算目录显示页码到 PDF 物理页码的偏移，并以正文已知标题进行校验。
- [x] 目录页不输出到最终正文 Block；目录条目进入 Parser 审核元数据。
- [x] 正文标题按规范化文本、编号和页码范围匹配目录；重复标题必须结合页码和当前路径消歧。
- [x] 对未匹配标题使用文档级字号、间距、缩进、全宽和序列规则恢复，禁止仅凭单行字号判一级标题。
- [x] 高置信度但层级不确定的标题默认按二级标题；低置信度候选保留诊断，不伪造路径。

### Task E4：测试、重新分块与审核 Gate

**Files:**

- Create/Modify: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/chunk/ParagraphChildPlannerTest.java`
- Create: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfTocReferenceExtractorTest.java`
- Create: `tools/rag-indexer/src/test/java/com/hirain/aiagent/rag/indexer/parser/pdf/PdfBlockAssemblerTest.java`

- [x] 覆盖 1～29、30～100、100～159、160 以上 Paragraph 的全部路径。
- [x] 覆盖前吸附、后吸附、512 上限、结构原子边界和无法吸附诊断。
- [x] 覆盖目录页识别、目录页排除、页码偏移、一级/二级标题匹配和未匹配标题回退。
- [x] 从新输出目录执行全量自动化测试和 Model Y `validate`。
- [x] 生成新的 Parser/Chunk 审核页面。
- [ ] 人工确认通过；确认前不得进入旧计划 Phase 2。

**执行状态（2026-07-25）：** E1～E3 与 E4 的自动化部分已完成；新的 Parser/Chunk 审核页面已经生成，但人工确认尚未完成，因此仍停在审核 Gate，未进入旧计划 Phase 2。
