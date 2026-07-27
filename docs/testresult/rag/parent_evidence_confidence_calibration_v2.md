# Parent Evidence V2 置信度校准审计

日期：2026-07-27  
数据集：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`  
Bundle：`TEST_ONLY-model-y-2026-refresh-v4-expanded`

## 当前数据分组

当前审核后的评测集共 33 条：DEV 9 条、TEST 24 条。该规模沿用“先跑通”口径，不把 33 条扩大解释为正式发布质量证明。

| 模式 | Split | 数量 | Coverage@1 | Coverage@2 | Coverage@4 | MRR |
|---|---:|---:|---:|---:|---:|---:|
| RRF | DEV | 9 | 7/9 (0.7778) | 8/9 (0.8889) | 9/9 (1.0000) | 0.8611 |
| Rerank | DEV | 9 | 7/9 (0.7778) | 7/9 (0.7778) | 8/9 (0.8889) | 0.8148 |
| RRF | TEST | 24 | 19/24 (0.7917) | 23/24 (0.9583) | 23/24 (0.9583) | 0.8750 |
| Rerank | TEST | 24 | 20/24 (0.8333) | 21/24 (0.8750) | 23/24 (0.9583) | 0.8785 |

## 结论

1. 当前 DEV 结果足以说明 RRF/Rerank 链路可运行并可比较，但样本数量和分数诊断不足以锁定稳定的 HIGH/MEDIUM/LOW 分数阈值。
2. Rerank 在当前 TEST 集只改善 Coverage@1 和 MRR，Coverage@2 低于 RRF；不能把 Rerank 作为整体优于 RRF 的结论。
3. 当前 Android 继续输出 `retrievalConfidence=UNASSESSED` 是有意的安全行为：没有把“排名靠前”伪装成概率，也没有使用 TEST 结果反向调参。
4. 本轮已补齐离线报告字段 `bestChildRerankScore`、`marginToNextParent`、`rankingSource`，并生成 `evaluation_parent_evidence_v2_rerank_calibration.json`。

## DEV 阈值选择

采用保守的二维阈值（同一 `qwen3-rerank`、同一候选预算）：

- HIGH：`score >= 0.94` 且 `margin >= 0.05`；DEV 2 条，Evidence@2 正确 2/2。
- MEDIUM：`score >= 0.90` 且 `margin >= 0.02`，但未达到 HIGH；DEV 4 条，Evidence@2 正确 3/4。
- LOW：其余有分数的 Rerank Parent；DEV 3 条，Evidence@2 正确 2/3。
- RRF 或缺少分数/间隔：`UNASSESSED`，不套用 Rerank 阈值。

## TEST 一次性确认

按上述 DEV 阈值对 TEST 进行一次性确认：HIGH 5 条（5/5）、MEDIUM 9 条（8/9）、LOW 10 条（8/10）。该结果只验证阈值迁移情况，不反向调整阈值。

## 后续 Gate

- 不修改当前 Chunk、RRF、Rerank 或去重参数。
- 不提升 Bundle 为 `APPROVED`，不复制到 `app/src/main/assets`。
- 下一步应先补齐分数/间隔诊断与人工结论映射，再执行 DEV 校准和一次性 TEST 确认。
