package com.hirain.aiagent.rag.indexer.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;

import java.io.IOException;
import java.nio.file.Path;

/** Manifest 不能成为自证材料；必须重开 Store 并与唯一 Metadata 相互核对。 */
public final class ManifestStoreConsistencyValidator {
    public void validate(Path manifestFile, Path storeDirectory) throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(manifestFile.toFile());
        try (var store = MyObjectBox.builder().directory(storeDirectory.toFile()).build()) {
            var metadataBox = store.boxFor(KnowledgeStoreMetadataEntity.class);
            if (metadataBox.count() != 1) throw new IllegalArgumentException("Store Metadata 数量非法");
            KnowledgeStoreMetadataEntity metadata = metadataBox.getAll().get(0);
            equal(manifest, "bundleId", metadata.bundleId); equal(manifest, "bundleVersion", metadata.bundleVersion);
            equal(manifest, "knowledgeScopeId", metadata.knowledgeScopeId); equal(manifest, "schemaFingerprint", metadata.schemaFingerprint);
            equal(manifest, "objectBoxVersion", metadata.objectBoxVersion); equal(manifest, "sourceLocatorSchemaVersion", metadata.sourceLocatorSchemaVersion);
            equal(manifest.path("embedding"), "provider", metadata.embeddingProvider); equal(manifest.path("embedding"), "model", metadata.embeddingModel);
            equal(manifest.path("embedding"), "dimension", metadata.embeddingDimension); equal(manifest.path("embedding"), "distanceType", metadata.distanceType);
            equal(manifest.path("parsers"), "configHash", metadata.parserConfigHash); equal(manifest.path("chunking"), "configHash", metadata.chunkingConfigHash);
            equal(manifest.path("corpus"), "corpusHash", metadata.corpusHash); equal(manifest.path("corpus"), "documentCount", metadata.documentCount);
            equal(manifest.path("corpus"), "parentChunkCount", metadata.parentChunkCount); equal(manifest.path("corpus"), "childChunkCount", metadata.childChunkCount);
            equal(manifest.path("corpus"), "lexicalTermCount", metadata.lexicalTermCount);
        }
    }
    private static void equal(JsonNode node, String field, String expected) { if (!expected.equals(node.path(field).asText())) throw new IllegalArgumentException("Manifest/Store 字段不一致：" + field); }
    private static void equal(JsonNode node, String field, long expected) { if (node.path(field).asLong(Long.MIN_VALUE) != expected) throw new IllegalArgumentException("Manifest/Store 字段不一致：" + field); }
}
