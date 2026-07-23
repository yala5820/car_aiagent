# 离线 Chunk 与检索评测报告

状态：`TEST_ONLY_BASELINE_COMPLETED`。

HTML-only Pilot 已完成最小版本化 Dense 评测。评测命令只读打开 ObjectBox Store，只将规范化后的 Query 发送给既有 Embedding 服务；报告只保存 Query SHA-256、Chunk ID、排名和指标，不写入 Query 正文或文档正文。历史结果中的 `Dense` 指仅向量检索；自 V4 起，命令行评测器会复现 Android 端当前的 Dense、BM25 与 RRF 融合链路，以避免用单一路径结果替代实际运行时判断。

| 项目 | 结果 |
|---|---|
| Dataset Version | `model-y-2026-refresh-html-pilot-v1` |
| Scope | `model-y-2026-cn-2026-refresh-rwd` |
| Bundle `data.mdb` SHA-256 | `675e938e7de530e9067efbeb135f1271447016ce649f2db8ccbb37ac56e3167f` |
| Case 数量 | 3（电池/驱动电机、座椅安全带/气囊、车辆基本质量保证） |
| Dense Recall@1 / @3 / @5 | `1.0 / 1.0 / 1.0` |
| MRR | `1.0` |

同一 3 条 HTML 用例已在扩充后的 PDF+HTML Bundle 上复跑：

| 项目 | 结果 |
|---|---|
| Bundle `data.mdb` SHA-256 | `9e8461b53032bbbc6e618bce132f817b636e19205554406501f6bffeceaae874` |
| 文档 / Parent / Child | `2 / 1,993 / 4,360` |
| Case 数量 | `3`（仍仅覆盖 HTML，不覆盖 PDF） |
| Dense Recall@1 / @3 / @5 | `0.667 / 0.667 / 0.667` |
| MRR | `0.667` |

扩充 PDF 语料后，原 HTML-only 小样本指标下降，说明检索竞争、Chunk 切分和候选数量已发生实质变化；该结果应作为 G903 校准输入，而不是静默沿用 HTML-only 的 `1.0` 基线。两次评测均成功产出脱敏报告，未保存 Query 正文或文档正文。

随后，Chunk 边界参数已从代码硬编码迁移为版本化配置（`maxChildTokens=256`、`overlapTokens=32`、`tableRowsPerChild=20`），并以新配置重建独立的 PDF+HTML `TEST_ONLY` Bundle：

| 项目 | 结果 |
|---|---|
| Bundle | `model-y-2026-refresh-chunk-baseline-v2` |
| Run ID | `a0791545-d3b3-4dcb-b981-bdcd449b9a9f` |
| Bundle `data.mdb` SHA-256 | `21106cd8fcf72639b7cf2d0b81345b42c73d17a8762f028fe812cbe7503ef296` |
| Publishable | `false`（`TEST_ONLY`） |
| 文档 / Parent / Child / 词项 | `2 / 1,993 / 4,360 / 87,990` |
| 独立 Verify | `VERIFY_SUCCESS` |
| 既有 3 条 HTML 用例 Dense Recall@1 / @3 / @5 | `1.0 / 1.0 / 1.0` |
| MRR | `1.0` |

该结果证明参数已可复现地进入 Bundle 指纹与构建链路；仍只覆盖 HTML 用例，不能据此判断 PDF 检索质量或批准正式阈值。

该样本量仅证明评测命令、Query Embedding、只读 Dense 查询和脱敏报告链路可用，不能用于锁定阈值、评价完整车型知识、代替 Android Hybrid/Rerank、No-Evidence、Citation 或最终回答 Faithfulness 评测。

## PDF + HTML 最小跨格式基线 V2

在资料负责人完成 PDF/HTML 本机预览审核后，新增两条直接关联已审核 PDF 正文分块的用例，并和既有三条 HTML 用例组成 5 条最小跨格式基线。评测使用配置已版本化的 `model-y-2026-refresh-chunk-baseline-v2` Bundle，报告仍只保存 Query SHA-256、Chunk ID 与排名。

| 项目 | 结果 |
|---|---|
| Dataset Version | `model-y-2026-refresh-pdf-html-baseline-v2` |
| Bundle `data.mdb` SHA-256 | `21106cd8fcf72639b7cf2d0b81345b42c73d17a8762f028fe812cbe7503ef296` |
| Case 数量 | `5`（HTML 3 条；PDF 2 条） |
| Dense Recall@1 / @3 / @5 | `0.600 / 1.000 / 1.000` |
| MRR | `0.767` |
| 评测报告 | `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/evaluation-pdf-html-baseline-v2-grounded.json`（本地忽略） |

两条 PDF 用例中，“儿童乘坐副驾驶座椅时的前排乘客安全气囊状态”在第 3 位召回；“通过 Tesla 手机应用启用/禁用暖气或空调并查看驾驶室温度”在第 2 位召回。后者的初始单一 Ground Truth 只包含概览分块，导致一次临时评测显示前 5 位未命中；逐条阅读 Top5 后确认其中 4 条是同样直接回答该问题的、更详细的 PDF 正文，因此将它们显式加入该用例的允许命中集，再次测评得出上表结果。初始严格报告保留在 `work/evaluation-pdf-html-baseline-v2.json`，不覆盖。

因此该基线**验证了跨格式评测链路，但没有达到可以锁定检索阈值的质量水平**：两个 PDF 问题都不是 Top1。下一步应基于这两个排序失败样本分析查询改写、Chunk 边界与混合检索/Rerank 的贡献，不能将 `TEST_ONLY` Bundle 提升为正式候选。

经批准的无密码、允许内容提取的 Owner's Manual PDF 已完成只读解析并纳入 PDF+HTML `TEST_ONLY` Bundle；Markdown、完整资料、正式人工 Metadata 审核、规模化 PDF/HTML/Markdown 评测集、Android Hybrid/Rerank、No-Evidence、Citation 和 Faithfulness 验收仍待补齐。

## HNSW 显式参数校准：V2 与 V3

V2 的 ObjectBox HNSW 配置只在注解中隐式使用 Runtime 默认值，Manifest 仅记录维度/Cosine 指纹，不能作为跨端发布协议。为消除这一缺口，V3 显式写入 `neighborsPerNode=30`、`indexingSearchCount=100`、`reparationBacklinkProbability=1.0`、`vectorCacheHintSizeKB=0` 和空 flags，并在离线与 Android 端严格校验。

| 候选 | `data.mdb` SHA-256 | 同批次 5 条 PDF+HTML Dense 指标 | 结论 |
|---|---|---|---|
| V2 `model-y-2026-refresh-chunk-baseline-v2` | `21106cd8fcf72639b7cf2d0b81345b42c73d17a8762f028fe812cbe7503ef296` | Recall@1/@3/@5=`0.600/1.000/1.000`，MRR=`0.767` | 旧隐式协议，仅作对照，不可作为新跨端候选。 |
| V3 `model-y-2026-refresh-hnsw-explicit-v3` | `48ac68254d07ed24ed5bd90d5499315af3d93146704dbd90eb667f60a35d9d47` | Recall@1/@3/@5=`0.200/0.600/0.600`，MRR=`0.367` | `VERIFY_SUCCESS`，但当前参数显著劣于 V2 对照，不能批准。 |

两次评测使用同一份 `model-y-2026-refresh-pdf-html-baseline-v2` Ground Truth，并在同一批次重新计算 V2，避免把云端 Query Embedding 的时间差误判为 HNSW 差异。V3 中 3 条 HTML 用例未进入 Top5，而 V2 同批次均在 Top1。该结果仅证明当前 V3 参数不适合批准，不足以推断 ObjectBox Runtime 默认值的内部实现；后续必须以新的独立 `TEST_ONLY` 候选比较其他显式 HNSW Profile，并同时记录 Android 查询延迟。

## V4 扩展资料与 Android 对齐 Hybrid 评测（2026-07-23）

### 评测目的与边界

本轮不再通过调大 HNSW 参数碰运气，而是先处理已确认的质量根因，再以与 Android 当前运行时一致的检索顺序进行复测：新增的 DIY 操作指南目录聚合为一个逻辑 `STATIC_HTML` 文档；中国大陆服务中心 HTML 只读取页面内嵌 Next.js JSON，不执行 JavaScript、不访问网络；评测集由 5 条扩展到 20 条，覆盖保修、维护、DIY 操作、服务预约和北京/上海服务中心信息。

离线 Hybrid 评测器复现 Android 的 Dense Top 20、BM25 Top 20、RRF（`k=60`）融合和 Top 5 截断。本轮没有引入云端 Rerank，报告模式为 `HYBRID_FUSION_ONLY_V1`。候选始终是 `TEST_ONLY`，不构成正式 APK 交付或生产发布结论。

### 候选包与解析结果

| 项目 | 结果 |
| --- | --- |
| 候选目录 | `tools/rag-indexer/trial-output/model-y-2026-refresh-expanded-v4` |
| Bundle 版本 | `TEST_ONLY-model-y-2026-refresh-v4-expanded` |
| 逻辑文档数 | 4（用户手册 PDF、保修 HTML、DIY HTML 目录、服务中心 HTML） |
| Parent / Child Chunk | 2,401 / 5,242 |
| 倒排词项数 | 100,561 |
| 向量生成 | 5,242 / 5,242 成功 |
| 独立验证 | `VERIFY_SUCCESS` |
| 数据库 SHA-256 | `d132b8c9da49d1c41328a336d21db81f72e1e21daae2e0f854191e5f1959bb72` |

DIY 聚合文档产生 878 个正文块、6 个表格块；服务中心静态数据产生 572 个正文块。DIY 原始文件不被修改，构建前会排除导航用 `index*.html`，并对最多 96 个内容页按相对路径和文件哈希生成确定性来源指纹。

### 指标结果

评测数据为 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_pdf_html_diy_service_v4.json`，共 20 条；所有 `expectedChunkId` 均已在 V4 数据库中验证存在。

| 指标 | Dense-only V4 | Android 对齐 Hybrid V4 | 说明 |
| --- | ---: | ---: | --- |
| Recall@1 | 0.4000 | 0.5000 | 首位命中仍有提升空间，不能据此宣称生产可用 |
| Recall@3 | 0.8500 | 0.8500 | 融合没有降低前三名召回 |
| Recall@5 | 0.8500 | 1.0000 | 20 条预期证据均进入最终 Top 5 |
| MRR | 0.6000 | 0.6908 | 目标证据整体排序前移 |

原始报告位于：Dense 基线 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/evaluation-pdf-html-diy-service-v4.json`；Hybrid 复测 `tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/evaluation-pdf-html-diy-service-hybrid-v4.json`。

### 根因、改动与效果

| 已确认根因 | 处理方式 | 可观察效果 |
| --- | --- | --- |
| 新增 DIY 与服务中心资料此前未进入知识库 | 以 2 个受控逻辑来源接入，而非将 DIY 的 64 个内容页分别注册为文档 | 覆盖从 2 个逻辑文档扩展至 4 个，仍满足初期文档数预算 |
| 服务中心页面正文依赖内嵌 JSON，安全解析器移除脚本后没有可索引正文 | 增加仅识别 `__NEXT_DATA__` 的受限提取器，将名称、城市、地址和电话转为正文块 | 北京、上海等服务中心信息可以被检索并提供引用证据 |
| 旧评测只测 Dense，而 App 实际使用 Dense + BM25 + RRF | 新增离线 Hybrid 评测器，参数与 App 的 Top20 / `k=60` 保持一致 | Recall@5 从 0.85 提升至 1.00，MRR 从 0.6000 提升至 0.6908 |
| V3 调大 HNSW 参数没有正收益 | 保持已验证的索引配置，不继续盲调近邻图参数 | 优化重心回到语料、Chunk 与融合检索等可解释因素 |

### 当前结论与未关闭项

V4 已证明：在 20 条初期验收问题上，Android 当前 Hybrid 链路能够在最终 Top 5 找到全部预期证据。后续已在 Automotive AVD 将 V4 作为 `androidTest` 资产完成真实流式安装、Manifest/Hash/Scope/Metadata 校验，以及 Android ObjectBox 上的 BM25 与 Dense 查询；它证明跨端 Store 可用，但不是 Agent 端到端回答验收。`Recall@1=0.50` 仍偏低，尚未完成真实 Query Embedding/RRF、引用和回答忠实度测试，也没有完成云端 Rerank、无证据/冲突/车型地区不匹配负向用例与 PDF 表格质量验收。

此外，DIY 聚合后保留了原始 HTML 锚点，但 `SourceLocator` 尚未持久化原始相对文件路径；如正式交付要求精确定位到某个 DIY 页面，需单独扩展定位协议。因此该候选继续保持 `TEST_ONLY`；下一步应将 V4 作为测试资产接入 Android 端进行冒烟与引用验收，再依据真实运行结果决定是否进入 `APPROVED` 发布流程。
