package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.MyObjectBox;
import java.nio.file.Path;

/** Manifest 与唯一 Metadata 必须逐项同源；任一差异都禁止激活数据库。 */
public final class KnowledgeStoreCompatibilityValidator {
    /** 打开候选 Store 后只读取唯一 Metadata；失败时不让半打开实例进入 Active Manager。 */
    public KnowledgeStoreValidationResult validateStoreDirectory(Path directory, KnowledgeBundleManifest manifest) {
        try (var store = MyObjectBox.builder().directory(directory.toFile()).build()) {
            var values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
            if (values.size() != 1) return KnowledgeStoreValidationResult.failure("STORE_METADATA_COUNT_INVALID");
            return validate(manifest, values.get(0));
        } catch (Exception error) {
            return KnowledgeStoreValidationResult.failure("STORE_OPEN_FAILED");
        }
    }
    public KnowledgeStoreValidationResult validate(KnowledgeBundleManifest manifest, KnowledgeStoreMetadataEntity metadata) {
        if (manifest == null || metadata == null) return KnowledgeStoreValidationResult.failure("STORE_METADATA_MISSING");
        if (metadata.formatVersion != manifest.formatVersion() || metadata.sourceLocatorSchemaVersion != manifest.sourceLocatorSchemaVersion()) return KnowledgeStoreValidationResult.failure("STORE_VERSION_MISMATCH");
        if (!equals(manifest.bundleId(), metadata.bundleId) || !equals(manifest.bundleVersion(), metadata.bundleVersion) || !equals(manifest.knowledgeScopeId(), metadata.knowledgeScopeId) || !equals(manifest.schemaFingerprint(), metadata.schemaFingerprint)) return KnowledgeStoreValidationResult.failure("STORE_MANIFEST_ID_MISMATCH");
        if (!equals(manifest.embedding().provider(), metadata.embeddingProvider) || !equals(manifest.embedding().model(), metadata.embeddingModel) || manifest.embedding().dimension() != metadata.embeddingDimension || !equals(manifest.embedding().distanceType(), metadata.distanceType) || manifest.embedding().templateVersion() != metadata.embeddingTemplateVersion) return KnowledgeStoreValidationResult.failure("STORE_EMBEDDING_MISMATCH");
        if (!equals(manifest.hnsw().configFingerprint(), metadata.hnswConfigFingerprint) || manifest.lexicalAnalyzerVersion() != metadata.lexicalAnalyzerVersion || metadata.lexicalFieldVersion != KnowledgeStoreContract.LEXICAL_FIELD_VERSION || Double.compare(metadata.bm25TitleWeight, KnowledgeStoreContract.BM25_TITLE_WEIGHT) != 0 || Double.compare(metadata.bm25BodyWeight, KnowledgeStoreContract.BM25_BODY_WEIGHT) != 0 || !equals(manifest.parserConfigHash(), metadata.parserConfigHash) || !equals(manifest.chunkingConfigHash(), metadata.chunkingConfigHash) || !equals(manifest.corpus().corpusHash(), metadata.corpusHash)) return KnowledgeStoreValidationResult.failure("STORE_PROTOCOL_MISMATCH");
        if (manifest.corpus().documentCount() != metadata.documentCount || manifest.corpus().parentChunkCount() != metadata.parentChunkCount || manifest.corpus().childChunkCount() != metadata.childChunkCount || manifest.corpus().lexicalTermCount() != metadata.lexicalTermCount) return KnowledgeStoreValidationResult.failure("STORE_COUNT_MISMATCH");
        return KnowledgeStoreValidationResult.success();
    }
    private static boolean equals(String left, String right) { return left != null && left.equals(right); }
}
