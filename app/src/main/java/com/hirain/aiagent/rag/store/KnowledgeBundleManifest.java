package com.hirain.aiagent.rag.store;

import java.util.List;
import java.util.Map;

/** 已通过严格 Parser 的 Bundle Manifest；集合均不可变，禁止调用方补写未校验字段。 */
public record KnowledgeBundleManifest(int formatVersion, String bundleId, String bundleVersion, String knowledgeScopeId,
                                      String builderVersion, long builtAtEpochMs, String objectBoxVersion, String schemaFingerprint,
                                      int sourceLocatorSchemaVersion, DataFile dataFile, Embedding embedding, Hnsw hnsw,
                                      Map<String, String> scope, String parserConfigHash, List<String> supportedSourceFormats,
                                      int lexicalAnalyzerVersion, String chunkingConfigHash, Corpus corpus) {
    public KnowledgeBundleManifest { scope = Map.copyOf(scope); supportedSourceFormats = List.copyOf(supportedSourceFormats); }
    public record DataFile(String name, long sizeBytes, String sha256) { }
    public record Embedding(String provider, String model, int dimension, String distanceType, int templateVersion) { }
    /** HNSW 参数必须与共享 Entity 注解完全一致，避免 Manifest 指纹与实际索引脱节。 */
    public record Hnsw(String configFingerprint, int neighborsPerNode, int indexingSearchCount,
                       float reparationBacklinkProbability, int vectorCacheHintSizeKB, List<String> flags) {
        public Hnsw { flags = List.copyOf(flags); }
    }
    public record Corpus(String corpusHash, long documentCount, Map<String, Long> sourceFormatCounts, long parentChunkCount,
                         long childChunkCount, long lexicalTermCount) { public Corpus { sourceFormatCounts = Map.copyOf(sourceFormatCounts); } }
}
