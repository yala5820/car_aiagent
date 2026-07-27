package com.hirain.aiagent.rag.indexer.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.chunk.EmbeddingTextRenderer;
import com.hirain.aiagent.rag.indexer.lexical.LexicalAnalyzerConfig;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;

import java.util.TreeMap;
import java.util.TreeSet;

/** Metadata 的集合字段统一序列化为排序 JSON，禁止由 HashMap 迭代顺序决定 Bundle 内容。 */
public final class KnowledgeStoreMetadataMapper {
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeStoreMetadataEntity map(StoreMetadataInput input) {
        try {
            KnowledgeStoreMetadataEntity entity = new KnowledgeStoreMetadataEntity();
            entity.bundleId = input.bundleId(); entity.bundleVersion = input.bundleVersion(); entity.knowledgeScopeId = input.knowledgeScopeId();
            entity.formatVersion = KnowledgeStoreContract.FORMAT_VERSION; entity.schemaFingerprint = input.schemaFingerprint();
            entity.builderVersion = input.builderVersion(); entity.objectBoxVersion = input.objectBoxVersion();
            entity.embeddingProvider = KnowledgeStoreContract.EMBEDDING_PROVIDER; entity.embeddingModel = KnowledgeStoreContract.EMBEDDING_MODEL;
            entity.embeddingDimension = KnowledgeStoreContract.EMBEDDING_DIMENSION; entity.distanceType = KnowledgeStoreContract.DISTANCE_TYPE;
            entity.hnswConfigFingerprint = input.hnswConfigFingerprint(); entity.embeddingTemplateVersion = EmbeddingTextRenderer.TEMPLATE_VERSION;
            entity.lexicalAnalyzerVersion = KnowledgeStoreContract.LEXICAL_FIELD_VERSION; entity.sourceLocatorSchemaVersion = KnowledgeStoreContract.SOURCE_LOCATOR_SCHEMA_VERSION;
            entity.parserConfigHash = input.parserConfigHash(); entity.chunkingConfigHash = input.chunkingConfigHash(); entity.corpusHash = input.corpusHash();
            entity.supportedSourceFormats = mapper.writeValueAsString(new TreeSet<>(input.supportedSourceFormats()));
            entity.sourceFormatCounts = mapper.writeValueAsString(new TreeMap<>(input.sourceFormatCounts()));
            entity.documentCount = input.documentCount(); entity.parentChunkCount = input.parentChunkCount(); entity.childChunkCount = input.childChunkCount();
            entity.lexicalTermCount = input.lexicalTermCount();
            entity.averageLexicalTitleLength = input.averageLexicalTitleLength();
            entity.averageLexicalBodyLength = input.averageLexicalBodyLength();
            entity.averageLexicalDocumentLength = input.averageLexicalBodyLength();
            entity.bm25TitleWeight = KnowledgeStoreContract.BM25_TITLE_WEIGHT;
            entity.bm25BodyWeight = KnowledgeStoreContract.BM25_BODY_WEIGHT;
            entity.lexicalFieldVersion = KnowledgeStoreContract.LEXICAL_FIELD_VERSION;
            entity.builtAtEpochMs = input.builtAtEpochMs();
            return entity;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Store Metadata JSON 序列化失败", exception);
        }
    }
}
