package com.hirain.aiagent.rag.indexer.report;

/** Embedding 调用只记录数量/缓存/耗时，禁止记录 API Key 或完整输入文本。 */
public record EmbeddingBuildSummary(long requestedCount, long cacheHitCount, long succeededCount, long failedCount, long durationMs) { }
