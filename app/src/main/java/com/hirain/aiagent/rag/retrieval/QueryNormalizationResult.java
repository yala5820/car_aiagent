package com.hirain.aiagent.rag.retrieval;

/** 原始 Query 绝不在这里被替换；仅提供检索使用的规范化文本和稳定 Hash。 */
public record QueryNormalizationResult(boolean valid, String normalizedQuery, String queryHash, String failureReason) {
    public static QueryNormalizationResult failure(String reason) { return new QueryNormalizationResult(false, null, null, reason); }
}
