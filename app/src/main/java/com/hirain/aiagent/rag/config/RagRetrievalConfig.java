package com.hirain.aiagent.rag.config;

/** 检索基线集中定义，避免 Tool 或调用点私自改变候选数、RRF 常量或 BM25 参数。 */
public record RagRetrievalConfig(int queryMaxCodePoints, int lexicalTopK, int denseTopK, int rerankTopK, int finalEvidenceTopK, int rrfK, double bm25K1, double bm25B) {
public static RagRetrievalConfig v1() { return new RagRetrievalConfig(512, 20, 20, 20, 5, 60, 1.2d, 0.75d); }
/** Parent Evidence V2：Rerank 默认 15，最终最多返回 4 个完整 Parent。 */
public static RagRetrievalConfig v2() { return new RagRetrievalConfig(512, 20, 20, 15, 4, 60, 1.2d, 0.75d); }
}
