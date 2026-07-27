package com.hirain.aiagent.rag.store.entity;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * 一个 Store 仅允许一条当前元数据记录。字段使用稳定标量或确定性 JSON，
 * 使 Manifest、离线构建报告和 Android 激活校验可逐项比对。
 */
@Entity
public class KnowledgeStoreMetadataEntity {

    @Id
    public long id;
    public String bundleId;
    public String bundleVersion;
    public String knowledgeScopeId;
    public int formatVersion;
    public String schemaFingerprint;
    public String builderVersion;
    public String objectBoxVersion;
    public String embeddingProvider;
    public String embeddingModel;
    public int embeddingDimension;
    public String distanceType;
    public String hnswConfigFingerprint;
    public int embeddingTemplateVersion;
    public int lexicalAnalyzerVersion;
    public int sourceLocatorSchemaVersion;
    public String parserConfigHash;
    public String supportedSourceFormats;
    public String sourceFormatCounts;
    public String chunkingConfigHash;
    public String corpusHash;
    public long documentCount;
    public long parentChunkCount;
    public long childChunkCount;
    public long lexicalTermCount;
    public double averageLexicalDocumentLength;
    public double averageLexicalTitleLength;
    public double averageLexicalBodyLength;
    public double bm25TitleWeight;
    public double bm25BodyWeight;
    public int lexicalFieldVersion;
    public long builtAtEpochMs;

    public KnowledgeStoreMetadataEntity() {
    }
}
