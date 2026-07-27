# Parent Evidence Eval V2 当前评测结果

## 1. 输入

- Eval：`tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json`
- 数据集版本：`model-y-2026-refresh-parent-evidence-v2-reviewed-final`
- Case 数：36
- Bundle：`tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`
- Bundle data SHA-256：`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`
- 两种模式均使用同一 Bundle、同一 Eval 和同一 Parent Evidence 预算。

## 2. 指标对比

| 指标 | RRF | RRF + Child Rerank |
|---|---:|---:|
| Case 数 | 36 | 36 |
| Parent Evidence Coverage@1 | 0.5278 | 0.5000 |
| Parent Evidence Coverage@2 | 0.7222 | 0.6111 |
| Parent Evidence Coverage@3 | 0.7500 | 0.7222 |
| Parent Evidence Coverage@4 | 0.7778 | 0.7778 |
| Parent Evidence MRR | 0.6412 | 0.6065 |
| 最大 Evidence Token | 4868 | 4821 |
| Evidence Token 总量 | 71914 | 86337 |
| Exact Duplicate Drop | 19 | 19 |
| Near Duplicate Drop | 16 | 16 |
| Parent Occupancy Drop | 163 | 163 |

## 3. 未覆盖样本

### RRF 未覆盖

`diy-brake-fluid-check`、`low-voltage-battery-replacement`、`driver-profile`、`phone-key-setup`、`front-camera-calibration`、`coolant-check`、`pre-drive-check`、`camp-mode`。

### Rerank 未覆盖

`basic-vehicle-warranty`、`diy-brake-fluid-check`、`low-voltage-battery-replacement`、`software-update`、`driver-profile`、`phone-key-setup`、`coolant-check`、`camp-mode`。

当前结果表明：在这份 Eval 上，Rerank 没有带来整体收益；RRF 的 Coverage@1、@2、@3 和 MRR 均更高，Coverage@4 持平。该结论只描述当前 36 条测试集，不代表所有语料或 Android 真实链路。

## 4. 运行命令

```powershell
java -cp "tools/rag-indexer/build/classes/java/main;tools/rag-indexer/build/resources/main;tools/rag-indexer/build/install/rag-indexer/lib/*" `
  com.hirain.aiagent.rag.indexer.RagIndexerMain evaluate-v2 `
  --bundle tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2 `
  --dataset tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json `
  --report docs/testresult/rag/evaluation_parent_evidence_v2_rrf_current.json

java -cp "tools/rag-indexer/build/classes/java/main;tools/rag-indexer/build/resources/main;tools/rag-indexer/build/install/rag-indexer/lib/*" `
  com.hirain.aiagent.rag.indexer.RagIndexerMain evaluate-v2-rerank `
  --bundle tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2 `
  --dataset tools/rag-indexer/corpus/model_y_2026_refresh_trial/evaluation_parent_evidence_v2.json `
  --report docs/testresult/rag/evaluation_parent_evidence_v2_rerank_current.json
```

报告只保存 Query Hash、Case ID、Parent 排名和计数，不保存 Query 正文。
