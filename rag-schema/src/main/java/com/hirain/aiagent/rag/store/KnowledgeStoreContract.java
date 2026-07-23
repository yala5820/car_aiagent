package com.hirain.aiagent.rag.store;

import java.util.List;
import java.util.Map;

/**
 * RAG Store 的跨端不变量。离线端写入、Android 端激活和 Manifest 校验都必须使用
 * 同一组值，避免把 Embedding 维度或 Locator 空值语义散落成多个隐式约定。
 */
public final class KnowledgeStoreContract {

    public static final int FORMAT_VERSION = 1;
    public static final int EMBEDDING_DIMENSION = 1024;
    public static final String EMBEDDING_PROVIDER = "DashScope";
    public static final String EMBEDDING_MODEL = "text-embedding-v4";
    public static final String DISTANCE_TYPE = "COSINE";
    /** ObjectBox HNSW 的显式 V1 基线；避免依赖库升级时默认值漂移而未被协议发现。 */
    public static final int HNSW_NEIGHBORS_PER_NODE = 30;
    public static final int HNSW_INDEXING_SEARCH_COUNT = 100;
    public static final float HNSW_REPARATION_BACKLINK_PROBABILITY = 1.0F;
    /** 0 表示交给已锁定的 ObjectBox Runtime 使用其默认缓存策略，不预分配车机内存。 */
    public static final int HNSW_VECTOR_CACHE_HINT_SIZE_KB = 0;
    public static final List<String> HNSW_FLAGS = List.of();
    public static final int SOURCE_LOCATOR_SCHEMA_VERSION = 1;
    public static final String EMPTY_NUMERIC_LOCATOR_SEMANTIC = "NOT_APPLICABLE_STORED_AS_ZERO";

    private KnowledgeStoreContract() {
    }

    /**
     * Manifest 与指纹使用同一份稳定字段集合；任何字段变化都必须触发全量重建和跨端验证。
     */
    public static Map<String, Object> hnswManifestDetails() {
        return Map.of(
                "neighborsPerNode", HNSW_NEIGHBORS_PER_NODE,
                "indexingSearchCount", HNSW_INDEXING_SEARCH_COUNT,
                "reparationBacklinkProbability", HNSW_REPARATION_BACKLINK_PROBABILITY,
                "vectorCacheHintSizeKB", HNSW_VECTOR_CACHE_HINT_SIZE_KB,
                "flags", HNSW_FLAGS
        );
    }
}
