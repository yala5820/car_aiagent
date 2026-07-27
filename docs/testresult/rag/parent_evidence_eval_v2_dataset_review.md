# Model Y Parent Evidence Eval V2 数据集审查说明

## 1. 数据集范围

- 文件：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`
- 当前人工审核页：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-authoring-v2.html`（含每条样本的 DEV/TEST 分组选择）
- 版本：`model-y-2026-refresh-parent-evidence-v2-reviewed`
- 车型范围：Model Y，2026 焕新版，中国大陆，后驱版
- 语料 Bundle：`tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`
- 样本数：50 条
- 可回答样本：50 条；无证据样本：0 条
- 当前发布状态：仅用于 `TEST_ONLY` 评测，不代表正式知识库已批准发布

## 2. 覆盖分布

| 类别 | 数量 | 覆盖内容 |
|---|---:|---|
| WARRANTY | 3 | 整车、动力电池/驱动单元、安全带和气囊保证 |
| VEHICLE_OPERATION | 17 | 充电排程、充电口、胎压、蓝牙、钥匙、软件和车身操作 |
| DIY_OPERATION | 16 | 滤清器、雨刮器、制动液、冷却液、轮胎、维护和安装操作 |
| SAFETY | 7 | 儿童乘坐、驾驶辅助、摄像头、碰撞警告和驾驶前检查 |
| SERVICE_CENTER | 5 | 北京、上海、成都、杭州和海口服务中心 |
| OTHER | 2 | 车辆数据隐私和 EDR |

数据包含单 Parent 证据、多 Parent 联合证据、同义/口语化问题、PDF 与静态 HTML 来源，以及容易被相近主题干扰的样本。每条样本的 `acceptableEvidenceSets` 使用集合间 OR、集合内 Parent 间 AND；Child 只作为可选定位字段，不作为主成功条件。

## 3. 自动校验结果

已完成以下校验：

1. Eval V2 严格 Schema 加载：字段、枚举、重复 case/query、ANSWERABLE/NO_EVIDENCE 约束通过。
2. Parent Ground Truth 校验：新版通过 `ParentEvidenceManualReviewWriter` 严格加载和 Parent 存在性校验，`parents=951 cases=50`。
3. 所有期望 Parent ID 均存在于标题协议 Bundle，且每个 Parent 至少有一个 Child。
4. 评测报告只保存 case ID、Query Hash、Parent/Child ID、排名和计数；Query 与正文仅保留在本地输入和审核页面，不进入可提交报告。

## 4. 人工复核边界

这份文件是自动校验和标注规则说明，不替代项目负责人对每条问题的最终语义审核。正式通过前，需在本地审核页逐条确认：

- 问题是否符合真实车主表达，而不是为了命中某个 Parent 反向拼接；
- 完整 Parent 是否足以覆盖 `answerCriteria`；
- 多 Parent 集合的 AND/OR 关系是否正确；
- NO_EVIDENCE 问题是否确实超出当前车型资料范围；
- PDF 表格、警告、服务中心字段是否被完整保留。

当前说明对应的是旧版 51 条草稿。该草稿已根据人工审核结果重建为新版 50 条评测集，旧文件仅作为修改前的追溯备份，不再作为下一轮指标输入。

## 6. 人工审核后的 V2 重建版

审核结果文件：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-review.json`

重建后的评测集仍使用同一路径：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`。修改规则如下：

| 处理 | 数量/结果 | 说明 |
|---|---:|---|
| 删除 | 17 条明确 `REMOVE` | 包括原来的 3 条无证据样本。它们没有 Evidence 是因为 `NO_EVIDENCE` 本来就要求 Evidence Set 为空；这批样本已按审核结论移除，避免与当前正向检索评测混在一起。 |
| 改写 | 2 条 `REWRITE` | 补齐“车辆基本有限质量保证”和“座椅安全带和气囊系统有限质量保证”的完整问句。 |
| Ground Truth 修正 | 2 条 | 空调滤清器问题改为只考察更换时间；电池/驱动单元期限问题补齐相邻 Parent 证据集合。 |
| 同义去重 | 多组 | 合并同一 Parent 下的工具、安装、电话、地址、解锁/启动等重复问法，保留一个能覆盖完整回答的问题。 |
| 新增 | 32 条 | 增加充电排程、手动释放、低压电池、维护、拖车、行车记录仪、软件更新、钥匙、驾驶员档案、摄像头、驾驶辅助、成都/杭州/海口服务中心等主题。 |

新版自动统计：

- 50 条 `ANSWERABLE` 样本，0 条 `NO_EVIDENCE` 样本；
- 50 个唯一 Query、50 个唯一主 Parent（电池期限样本额外引用相邻 Parent）；
- `DEV=20`、`TEST=30`，用于后续先校准再确认；
- 类别分布：`DIY_OPERATION=16`、`VEHICLE_OPERATION=17`、`SAFETY=7`、`WARRANTY=3`、`SERVICE_CENTER=5`、`OTHER=2`；
- 新审核页：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-authoring-v3.html`。

旧版原始数据已保存为：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/evaluation_parent_evidence_v2_before_review.json`。

新版只完成了结构、唯一性和 Parent 存在性校验，尚未把人工审核结果自动视为最终批准；后续指标应先使用 `DEV` 调整阈值，再对 `TEST` 做一次性确认。

## 5. 当前报告索引

- RRF 基线：`evaluation_parent_evidence_v2_rrf.json`
- RRF + 去重：`evaluation_parent_evidence_v2_rrf_final3.json`
- Rerank + 去重：`evaluation_parent_evidence_v2_rerank_final3.json`

上述报告均保持 Query 脱敏，并记录了 Parent Evidence Token 使用量、重复候选丢弃计数和无证据策略版本。
