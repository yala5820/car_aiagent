package com.hirain.aiagent.rag.indexer.embedding;

import java.util.Optional;

/** 缓存只保存已校验向量，损坏读取必须表现为 miss。 */
public interface EmbeddingCache {
    Optional<float[]> get(EmbeddingCacheKey key);
    void put(EmbeddingCacheKey key, float[] vector);
}
