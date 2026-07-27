package com.hirain.aiagent.rag.indexer.store;

import java.util.Map;
import java.util.Set;

/** 生成唯一 Store Metadata 所需的构建事实；调用方必须来自已冻结配置与统计，而非默认值。 */
public record StoreMetadataInput(
        String bundleId, String bundleVersion, String knowledgeScopeId, String schemaFingerprint, String builderVersion,
        String objectBoxVersion, String hnswConfigFingerprint, String parserConfigHash, String chunkingConfigHash,
        String corpusHash, Set<String> supportedSourceFormats, Map<String, Long> sourceFormatCounts,
        long documentCount, long parentChunkCount, long childChunkCount, long lexicalTermCount,
        double averageLexicalTitleLength, double averageLexicalBodyLength, long builtAtEpochMs) {
    /** 兼容 V1 测试/工具调用，旧 avgdl 视为 BODY，标题平均长度为 0。 */
    public StoreMetadataInput(String bundleId, String bundleVersion, String knowledgeScopeId, String schemaFingerprint,
                              String builderVersion, String objectBoxVersion, String hnswConfigFingerprint,
                              String parserConfigHash, String chunkingConfigHash, String corpusHash,
                              Set<String> supportedSourceFormats, Map<String, Long> sourceFormatCounts,
                              long documentCount, long parentChunkCount, long childChunkCount, long lexicalTermCount,
                              double averageLexicalDocumentLength, long builtAtEpochMs) {
        this(bundleId, bundleVersion, knowledgeScopeId, schemaFingerprint, builderVersion, objectBoxVersion,
                hnswConfigFingerprint, parserConfigHash, chunkingConfigHash, corpusHash, supportedSourceFormats,
                sourceFormatCounts, documentCount, parentChunkCount, childChunkCount, lexicalTermCount,
                0D, averageLexicalDocumentLength, builtAtEpochMs);
    }
}
