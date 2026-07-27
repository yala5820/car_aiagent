# Parent-Child Chunk V2 产物报告

## Bundle

- 目录：`tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2`
- 状态：`TEST_ONLY-model-y-2026-refresh-v4-expanded`
- Parent：951
- Child：1878
- 来源：1 个 PDF、3 个静态 HTML 逻辑来源
- `data.mdb` SHA-256：`f4394632d092b488f6b53f6601b044e1c2d093442bb80e40a7c4fdb03e21beb1`
- 独立 Verify：`VERIFY_SUCCESS`

## 检索字段协议

- Dense：二级标题 `parentTitle` 与 Child 正文按 Embedding 模板 V2 生成向量。
- BM25：TITLE 与 BODY 独立建 posting，初始权重 `TITLE × 2.0 + BODY × 1.0`。
- Parent 不参与 Dense/BM25，Parent embedding 保持为空。
- Child 保留 `parentId`、`headingPath`、`chunkIndex` 关联信息。

## 运行链对齐

```text
Child Dense/BM25 → RRF → Exact/Near/Parent 占位去重 → Child Rerank → Parent 聚合 → 完整 Parent Evidence
```

最终 Evidence 默认最多 4 个 Parent、总预算 5000 token，不截断完整 Parent；未完成置信度校准前输出 `UNASSESSED`。

## 当前风险

Chunk 结构和标题协议已经通过自动构建/验证，但尚未将该 Bundle 放入主 APK，也尚未完成目标 `arm64-v8a` 设备验收。`TEST_ONLY` 资产不得直接发布。
