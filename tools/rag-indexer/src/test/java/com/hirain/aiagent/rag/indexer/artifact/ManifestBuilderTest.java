package com.hirain.aiagent.rag.indexer.artifact;

import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.store.StoreWriteModel;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ManifestBuilderTest {
    @Test
    void shouldBuildManifestFromClosedDataFileAndLockedMetadata() throws Exception {
        var data = Files.createTempFile("data-", ".mdb"); Files.writeString(data, "closed-store");
        KnowledgeStoreMetadataEntity metadata = metadata();
        var model = new StoreWriteModel(metadata, List.of(), List.of(), new LexicalIndex("v1", Map.of(), Map.of(), 0D));
        var manifest = new ManifestBuilder().build(data, model,
                Map.of("vehicleModel","V1","modelYear","2026","region","CN","softwareVersion","1","configurationCode","base"),
                Map.of("pdf",Map.of(),"html",Map.of(),"markdown",Map.of()), hnsw(hash()));
        assertEquals(Files.size(data), manifest.dataFile().sizeBytes());
        assertEquals("bundle", manifest.bundleId()); assertEquals(1024, manifest.embedding().get("dimension"));
        assertEquals(30, manifest.hnsw().get("neighborsPerNode"));
        assertEquals(100, manifest.hnsw().get("indexingSearchCount"));
    }
    static KnowledgeStoreMetadataEntity metadata() {
        var value = new KnowledgeStoreMetadataEntity(); value.bundleId="bundle"; value.bundleVersion="1"; value.knowledgeScopeId="scope"; value.builderVersion="1"; value.builtAtEpochMs=0; value.objectBoxVersion="5.4.0"; value.schemaFingerprint=hash(); value.parserConfigHash=hash(); value.chunkingConfigHash=hash(); value.corpusHash=hash(); value.hnswConfigFingerprint=hash(); value.embeddingProvider="DashScope"; value.embeddingModel="text-embedding-v4"; value.embeddingDimension=1024; value.distanceType="COSINE"; value.embeddingTemplateVersion=1; value.lexicalAnalyzerVersion=1; value.sourceLocatorSchemaVersion=1; value.sourceFormatCounts="{}"; return value;
    }
    static String hash() { return "sha256:" + "a".repeat(64); }
    private static Map<String, Object> hnsw(String fingerprint) {
        var result = new java.util.LinkedHashMap<>(KnowledgeStoreContract.hnswManifestDetails());
        result.put("configFingerprint", fingerprint); return Map.copyOf(result);
    }
}
