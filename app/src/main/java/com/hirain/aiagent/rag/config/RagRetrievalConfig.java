package com.hirain.aiagent.rag.config;

/** 检索基线集中定义，避免 Tool 或调用点私自改变候选数、RRF 常量或 BM25 参数。 */
public record RagRetrievalConfig(int queryMaxCodePoints, int lexicalTopK, int denseTopK, int rerankTopK, int finalEvidenceTopK, int rrfK, double bm25K1, double bm25B) {
    public static RagRetrievalConfig v1() { return new RagRetrievalConfig(512, 20, 20, 20, 5, 60, 1.2d, 0.75d); }
}
