# Parent Evidence V2 检索消融报告

日期：2026-07-27  
Bundle：`TEST_ONLY-model-y-2026-refresh-v4-expanded`  
数据集：33 条（DEV 9、TEST 24）  
Bundle data SHA-256：`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`

## 全量结果

| 模式 | Coverage@1 | Coverage@2 | Coverage@3 | Coverage@4 | MRR | Exact Drop | Near Drop | Parent 占位 Drop |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Dense only | 0.6061 | 0.7576 | 0.8182 | 0.8788 | 0.7172 | 0 | 0 | 0 |
| BM25 only | 0.8485 | 0.9394 | 1.0000 | 1.0000 | 0.9141 | 0 | 0 | 0 |
| RRF raw | 0.7879 | 0.9394 | 0.9394 | 0.9394 | 0.8636 | 0 | 0 | 0 |
| RRF + Exact Dedup | 0.7879 | 0.9394 | 0.9394 | 0.9697 | 0.8712 | 13 | 0 | 147 |
| RRF + Exact/Near Dedup | 0.7879 | 0.9394 | 0.9394 | 0.9697 | 0.8712 | 13 | 9 | 147 |
| RRF + Dedup + Rerank | 0.8182 | 0.8485 | 0.9091 | 0.9394 | 0.8611 | 13 | 9 | 147 |
| Rerank 失败 → RRF fallback | 0.7879 | 0.9394 | 0.9394 | 0.9697 | 0.8712 | 13 | 9 | 147 |

## DEV / TEST 对照

| 模式 | DEV @1/@2/@4 | DEV MRR | TEST @1/@2/@4 | TEST MRR |
|---|---:|---:|---:|---:|
| Dense only | 0.7778 / 0.8889 / 0.8889 | 0.8333 | 0.5417 / 0.7083 / 0.8750 | 0.6736 |
| BM25 only | 0.8889 / 1.0000 / 1.0000 | 0.9444 | 0.8333 / 0.9167 / 1.0000 | 0.9028 |
| RRF raw | 0.7778 / 0.8889 / 0.8889 | 0.8333 | 0.7917 / 0.9583 / 0.9583 | 0.8750 |
| RRF + Exact/Near Dedup | 0.7778 / 0.8889 / 1.0000 | 0.8611 | 0.7917 / 0.9583 / 0.9583 | 0.8750 |
| RRF + Dedup + Rerank | 0.7778 / 0.7778 / 0.8889 | 0.8148 | 0.8333 / 0.8750 / 0.9583 | 0.8785 |

## 结论

1. 当前语料上 BM25-only 的 Parent Coverage 和 MRR 高于 Dense-only，说明标题字段化 BM25 对当前中文说明书问题集贡献明显；这不是删除 Dense 的建议，Dense 仍保留用于术语改写、语义召回和 Hybrid 兜底。
2. RRF + 去重相较 RRF raw 提升 MRR（0.8636 → 0.8712），并改善 Coverage@4（0.9394 → 0.9697）；Exact/Near 去重没有改变当前主排名，但降低了重复候选和同 Parent 占位。
3. Rerank 提升 Coverage@1（0.7879 → 0.8182），但降低 Coverage@2/@3/@4 和 DEV MRR；当前不能宣称 Rerank 全面优于 RRF。
4. Rerank fallback 与正常 RRF + Dedup 结果一致，证明云端 Rerank 不可用时不会改变本地证据链语义。

完整机器产物：`evaluation_parent_evidence_v2_ablation_v2.json`。本报告没有使用 TEST 结果反向调整检索参数；当前所有 Bundle 仍为 `TEST_ONLY`。

## 未关闭的严格 DoD

- 当前评测集没有 `NO_EVIDENCE` 样本，因此 No-Evidence 阈值没有统计学校准证据；运行时继续采用受控的 TEST_ONLY 默认策略。
- 当前 33 条是项目负责人批准的“先跑通”数据规模，不等同于原计划正式质量 Gate 的 50 条覆盖集。
