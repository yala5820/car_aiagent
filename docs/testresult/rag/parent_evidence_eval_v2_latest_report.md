# Parent Evidence Eval V2 最新评测结果

## 输入

- Eval：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`
- 数据集版本：`model-y-2026-refresh-parent-evidence-v2-reviewed-final`
- Case 数：33
- Case ID / Query 唯一性：33 / 33
- Bundle：`tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`
- Bundle data SHA-256：`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`

## 指标对比

| 指标 | RRF | RRF + Child Rerank |
|---|---:|---:|
| Case 数 | 33 | 33 |
| Parent Evidence Coverage@1 | 0.7879 | 0.8182 |
| Parent Evidence Coverage@2 | 0.9394 | 0.8485 |
| Parent Evidence Coverage@3 | 0.9394 | 0.9091 |
| Parent Evidence Coverage@4 | 0.9697 | 0.9394 |
| Parent Evidence MRR | 0.8712 | 0.8611 |
| 最大 Evidence Token | 4010 | 4821 |
| Evidence Token 总量 | 61395 | 72576 |
| Exact Duplicate Drop | 13 | 13 |
| Near Duplicate Drop | 9 | 9 |
| Parent Occupancy Drop | 147 | 147 |

## 未覆盖样本

- RRF：`pre-drive-check`
- Rerank：`basic-vehicle-warranty`、`software-update`

当前数据下，Rerank 提升了 Coverage@1，但降低了 Coverage@2/@3/@4 和 MRR；因此不能简单认定 Rerank 整体优于 RRF。

## 结果文件

- RRF：`docs/testresult/rag/evaluation_parent_evidence_v2_rrf_latest.json`
- Rerank：`docs/testresult/rag/evaluation_parent_evidence_v2_rerank_latest.json`
- 人工对比页：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/work/eval-v2-rrf-rerank-latest-review.html`

## 运行命令

```powershell
java -cp "tools/rag-indexer/build/classes/java/main;tools/rag-indexer/build/resources/main;tools/rag-indexer/build/install/rag-indexer/lib/*" `
  com.hirain.aiagent.rag.indexer.RagIndexerMain evaluate-v2 `
  --bundle tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2 `
  --dataset tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json `
  --report docs/testresult/rag/evaluation_parent_evidence_v2_rrf_latest.json

java -cp "tools/rag-indexer/build/classes/java/main;tools/rag-indexer/build/resources/main;tools/rag-indexer/build/install/rag-indexer/lib/*" `
  com.hirain.aiagent.rag.indexer.RagIndexerMain evaluate-v2-rerank `
  --bundle tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2 `
  --dataset tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json `
  --report docs/testresult/rag/evaluation_parent_evidence_v2_rerank_latest.json
```
