# Parent Evidence Eval V2 结果报告

## 1. 执行范围

本报告是旧版 51 条 Eval V2 样本的历史结果。新版 50 条评测集已根据人工反馈重建，尚未重新执行 RRF/Rerank 指标，因此本报告不能代表新版数据结果。

```text
Query → Dense/BM25 Child → RRF → Exact/Near/Parent 占位去重 → Child Rerank → Child→Parent → Parent 去重 → 完整 Parent Evidence 预算
```

Bundle 的 `data.mdb` SHA-256 为 `f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`, 仍为 `TEST_ONLY`。

## 2. 指标

| 模式 | Coverage@1 | Coverage@2 | Coverage@3 | Coverage@4 | MRR | NoEvidenceAccuracy |
|---|---:|---:|---:|---:|---:|---:|
| RRF + 去重 | 0.5625 | 0.7292 | 0.7500 | 0.8542 | 0.6788 | 0.3333 |
| RRF + 去重 + Rerank | 0.5417 | 0.6875 | 0.8125 | 0.8542 | 0.6667 | 0.6667 |

Rerank 提升了 Coverage@3，但在本批数据的 Top1、Top2 和 MRR 低于 RRF。因此不能写成“Rerank 全面提升”，需要使用冻结 DEV 集继续校准候选上限、去重阈值和 Rerank 预算。

差异样本的本地人工审核页：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-rerank-review-v1.html`。

## 3. Evidence 与去重诊断

- RRF 模式 Evidence 最大 4 个 Parent，最大使用 `4305 token`，低于 5000 token 硬上限。
- Rerank 模式 Evidence 最大使用 `4821 token`，未截断完整 Parent。
- 两种模式共删除完全重复 35 个、高相似 8 个、同 Parent 超过 2 个占位 298 个候选。
- Parent 最终只出现一次；Rerank 输入始终为 Child 的 `sectionPath + 正文`，不会发送完整 Parent。

## 4. 失败与风险

1. `NoEvidenceAccuracy` 仍未达到可发布标准。当前 `OFFLINE_SHARED_PHRASE_V1_TEST_ONLY` 只是评测诊断阈值，不应直接复制到线上拒答逻辑。
2. 该报告对应旧版 51 条样本，旧版全部标为 `TEST`；新版已冻结 `DEV=20`、`TEST=30`，但尚未用新版数据重跑指标。
3. 评测集的 Parent ID、字段和数量已自动验证，但问题质量、Evidence 是否足以覆盖 answerCriteria 仍需人工审核页逐条确认。
4. Android JVM、Debug APK 和 Lint 已通过；本轮 `connectedDebugAndroidTest` 已构建 V2 测试资产但因 AVD 未注册为 ADB device 返回 `No connected devices!`，设备 Gate 仍未关闭。

## 5. 结论

Parent Evidence 运行链、标题检索协议、去重和 Eval V2 已经具备可重复的 TEST_ONLY 验证能力；当前交付状态是“实现完成、自动评测完成、人工和设备 Gate 未完成”。在人工复核、DEV/TEST 冻结、置信度/无证据阈值校准和 Android Gate 完成前，不提升 Bundle 为 `APPROVED`，也不放入主 APK。
