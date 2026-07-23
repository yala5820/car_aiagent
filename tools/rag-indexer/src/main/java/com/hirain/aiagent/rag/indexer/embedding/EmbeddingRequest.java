package com.hirain.aiagent.rag.indexer.embedding;

/** 保留 Child 顺序，云端响应必须严格回映射该 index。 */
public record EmbeddingRequest(int inputIndex, String childId, String embeddingText) {
}
