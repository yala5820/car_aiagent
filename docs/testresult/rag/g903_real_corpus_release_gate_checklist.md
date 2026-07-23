# RAG-G903 真实语料校准与正式候选前置条件清单

状态：`IN_PROGRESS`。本清单用于把 `RAG-G903` 的人工确认、资料缺口和可复跑证据集中记录；它不是发布批准书，所有未通过项均禁止进入 `RAG-G904`。

## 已固定的试运行事实

| 项目 | 证据 | 当前状态 |
|---|---|---|
| Knowledge Scope | `model-y-2026-cn-2026-refresh-rwd`；`MODEL_Y / 2026 / CN / 2026_REFRESH / RWD` | 已固定 |
| 真实资料授权 | 用户已明确授权当前 PDF、静态 HTML 用于本地处理和云端 Embedding | 已确认，限本次试运行资料 |
| PDF 只读边界 | 无密码、允许内容提取的 PDF 可解析；不修改、不签名、不注释、不解密、不绕过权限 | 已确认 |
| PDF 解析 | 9,900 Block、9,900 Locator、0 Table、37 条解析诊断（35 条重复页眉/页脚、1 条忽略主动 URI、1 条表格提取失败）；561 个 WARNING 正文块定位 | 仅技术解析完成，待人工审核 |
| 静态 HTML 解析 | 48 Locator、0 Parser Warning | 仅技术解析完成，待人工审核 |
| 试运行 Bundle | `TEST_ONLY`，2 Document、1,993 Parent、4,360 Child；`verify` 已通过 | 不可发布 |
| Dense 复跑 | 5 条 PDF+HTML 最小基线在 V2 Bundle 上 Recall@1/@3/@5=`0.600/1.000/1.000`，MRR=`0.767`；两条 PDF 用例分别第 3、2 位命中 | 已验证跨格式离线链路；Top1 排序仍不足以审批阈值 |

## 本轮人工审核结论（2026-07-22）

资料负责人已在本地 `parser-review.html` 核对当前 PDF/HTML 处理结果，并确认：

- `PDF_REPEATED_HEADER_OR_FOOTER` 的当前排除结果无问题；
- `PDF_IGNORED_ACTION_URI` 可忽略，系统不得执行或提取该主动 URI；
- 静态 HTML 的当前清洗与正文范围无问题；
- Scope `MODEL_Y / 2026 / CN / 2026_REFRESH / RWD` 正确；
- PDF 表格当前不作为初期知识覆盖保证。`PDF_TABLE_EXTRACTION_FAILED` 以 `TEMPORARILY_ACCEPTED_TEST_ONLY` 记录：若用户问题的唯一依据位于 PDF 表格，当前 RAG 不得声称已具备可靠证据；正式发布前仍须重新评估该边界。
- 初期真实语料范围固定为 PDF + 静态 HTML；Markdown Parser 保持已实现和自动测试覆盖，但真实 Markdown 资料不作为本轮跑通前置。

## 你现在应如何完成 PDF 人工审核

本次实际审核报告由增强后的 `validate` 命令生成，位于以下相对位置（真实资料和 work 目录均不进入 Git）：

```text
tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/runs/
  fa9d6ada-abac-4734-a923-0dd8b6ff8fc2/
    parser-review.json
    parser-review.html
```

`parser-review.json` 仅给出 `documentId`、源文件相对路径、格式、PDF 物理页码、HTML 段序号、诊断码和 WARNING 块定位；它适合程序化汇总。`parser-review.html` 是供人阅读的本机折叠式预览：PDF 显示“实际保留正文”和“被排除的重复页眉/页脚候选原文 + 出现页码”，HTML 显示清洗后实际保留的正文块。预览不会上传或进入 Bundle，但因为包含获授权正文，只能保留在本地 work 目录。

### PDF：37 条解析诊断应如何处理

| 诊断码 | 数量 | 你需要做什么 | 可以通过的条件 |
|---|---:|---|---|
| `PDF_REPEATED_HEADER_OR_FOOTER` | 35 | 在报告列出的物理页打开原 PDF，确认被排除的重复行确实是页眉、页脚或页码，而不是车型适用条件、限制、步骤或警告正文。 | 35 条均确认“排除正确”；任一条误删正文即不通过，必须修 Parser 后全量重建。 |
| `PDF_IGNORED_ACTION_URI` | 1 | 确认该 PDF 的主动 URI/动作不是知识正文，也不需要被执行或提取。 | 确认“忽略正确”；系统始终不执行、打开或跟随该动作。 |
| `PDF_TABLE_EXTRACTION_FAILED` | 1 | 确认手册中的表格是否包含必须进入知识库的参数、限制、步骤或安全信息。当前诊断为全局定位，不能证明任一表格已正确提取。 | 若存在关键表格，当前版本不通过，必须修复 PDF 表格策略/Parser 后全量重建；仅当资料负责人确认不存在需保留的关键表格，才可将此项记录为“业务可接受的缺失”，仍需发布负责人批准。 |

`warningLocators` 中的 561 项是正文含“警告、注意、禁止、危险”等触发词后被标记为 WARNING 的块，不是 561 个错误。建议至少抽查：第一页、最后一页、每个高密度页（报告中块数最多的页）以及任意 10% 的其余页，确认 WARNING 块没有吞并相邻的普通步骤、条件或标题；若发现吞并，必须修复切分逻辑后重建。

### HTML：你需要做什么

本次静态 HTML 没有解析诊断，包含 47 个 Block、1 个表格、48 个 Locator。请在原始 HTML 中抽查页面标题、正文内容根、表格和首尾 Locator，确认没有把导航、页脚、Cookie、外链或无关车型内容写入正文。发现问题时修改 Corpus 的审核过 Selector/配置后全量重建，不直接改 ObjectBox。

## 必须完成的人工 Metadata 审核

每份真实资料需由资料负责人填写并确认，记录不得复制正文、凭证或完整 Query。

| 审核项 | Owner's Manual PDF | 车辆质量保证静态 HTML | 审核人 / 日期 / 结论 |
|---|---|---|---|
| 来源、使用授权与版本是否可追溯 | 已确认用于本次试运行 | 已确认用于本次试运行 | 资料负责人，2026-07-22 |
| 车型、年款、地区、软件版本、配置适用范围是否与 Scope 一致 | 已确认 | 已确认 | 资料负责人，2026-07-22 |
| `corpus.json` 的标题、类型、语言、版本、SHA-256 是否与原始资料一致 | 已确认当前声明可用于试运行 | 已确认当前声明可用于试运行 | 资料负责人，2026-07-22 |
| PDF 阅读顺序、标题、Locator 抽样是否准确；37 条 Warning 是否均已处置 | 重复页眉/页脚与主动 URI 已确认；表格提取失败暂以 `TEST_ONLY` 边界接受 | 不适用 | 资料负责人，2026-07-22 |
| HTML 内容根与排除噪声规则是否正确；表格与 Locator 抽样是否准确 | 不适用 | 已确认 | 资料负责人，2026-07-22 |
| 是否存在关键知识缺失、错列、Warning 分离或不可用 Locator | PDF 表格内容不作初期覆盖保证 | 未发现 | 资料负责人，2026-07-22 |

任何一项的结论为“不通过”或“待修复”时，必须修 Parser/配置后全量重建，不得直接编辑 ObjectBox 数据库。

## 如何判定是否可以把质量门禁提升为 `APPROVED`

`APPROVED` 不是把配置文件中的字符串直接替换成 `APPROVED`，而是发布负责人对以下证据的签字结论。只要任一项未完成，配置必须保持 `TEST_ONLY`：

1. 当前初期发布范围内的 PDF、静态 HTML 均已在 `corpus.json` 声明，且每份资料的 SHA-256、版本、标题、语言和五项 Scope 已人工确认。真实 Markdown 是后续资料扩展门禁：一旦纳入正式 Scope，必须先完成同等审核、解析与评测，不能静默沿用本轮批准。
2. 上述 37 条 PDF 诊断均有“通过 / 不通过 / 修复后重跑”的明确结论；特别是 `PDF_TABLE_EXTRACTION_FAILED` 不可仅因构建成功而忽略。
3. 当前初期范围的 PDF、HTML 各至少有经人工核对的评测 Query 与 expected Chunk ID；评测集版本、Bundle Hash、Recall@1/@3/@5、MRR 已写入报告。当前已有 PDF+HTML 最小基线，但样本量不足，不能批准。
4. 至少比较一组以上 Parent/Child 长度和 Overlap 配置，以及一组以上 HNSW 参数；记录体积、构建时间、Android 查询延迟和 Dense Recall 的取舍，并由负责人选择最终参数。当前 HNSW 注解只固定维度与 Cosine，未显式配置 `neighborsPerNode`、`indexingSearchCount` 等 API 支持的参数；指纹也未覆盖这些值，因此本项尚不可执行，必须先完成共享 Schema 协议修订。
5. 发布负责人写明并批准单文件/总语料/DOM/AST/Table/异常字符质量阈值；当前只有 `TEST_ONLY` 试运行值，尚未批准。
6. 使用最终 `APPROVED` 配置重新构建，`build-report.publishable=true`、CLI `verify` 通过后，才可进入 `RAG-G904`；此时仍不能直接写入 Android main Assets。

## 评测与参数批准门禁

| 门禁 | 当前证据 | 进入 `RAG-G904` 前必须补齐 |
|---|---|---|
| 版本化评测集 | 5 条 PDF+HTML 最小基线 | 扩充初期 PDF/HTML 样本；未来纳入 Markdown 后补 Markdown 评测用例 |
| Dense 指标 | V2 PDF+HTML 基线 Recall@1/@3/@5=`0.600/1.000/1.000`，MRR=`0.767` | 在完整真实语料、候选 Chunk 参数和 HNSW 参数下复跑并记录对比 |
| Chunk 参数 | 当前仅 `TEST_ONLY` 试运行配置 | Parent/Child 长度、Overlap、表格切分的对比结论和负责人批准 |
| HNSW 参数 | 仅维度/Cosine 被固定；可调参数未进入共享 Schema 或指纹 | 先修订共享 HNSW 协议，再完成体积、构建时间、Android 查询延迟与 Recall 对比和负责人批准 |
| 质量阈值 | 未批准 | 单文件/总语料/DOM/AST/Table/异常字符阈值与 `APPROVED` 状态 |
| Markdown 真实资料 | 未提供，且不在当前初期范围 | 后续纳入正式 Scope 前提供经授权静态 Markdown、Metadata、解析审核与评测用例 |
| Android 完整评测 | 不属于离线评测范围 | Hybrid/Rerank、No-Evidence、Citation、Faithfulness 按 Android 调度 Goal 验证 |

## 结论记录

只有所有上表“必须补齐”项完成，并由发布负责人将参数和质量门禁明确提升为 `APPROVED` 后，才允许创建 `RAG-G904` 的正式 Bundle 候选。此时仍不得写入 Android main Assets；必须继续经过 `RAG-G905` 跨端验证与 `RAG-G906` 交付记录收口。
