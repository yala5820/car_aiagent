package com.hirain.aiagent.rag.indexer.artifact;

import com.hirain.aiagent.rag.indexer.lexical.LexicalIndex;
import com.hirain.aiagent.rag.indexer.store.StoreWriteModel;
import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.KnowledgeStoreContract;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class ManifestStoreConsistencyPositiveTest {
    @Test
    void shouldAcceptManifestBuiltFromSameClosedStore() throws Exception {
        var directory = Files.createTempDirectory("rag-manifest-store-"); KnowledgeStoreMetadataEntity metadata = ManifestBuilderTest.metadata();
        try (var store = MyObjectBox.builder().directory(directory.toFile()).build()) { store.boxFor(KnowledgeStoreMetadataEntity.class).put(metadata); }
        var model = new StoreWriteModel(metadata, List.of(), List.of(), new LexicalIndex("v1", Map.of(), Map.of(), 0D));
        var manifest = new ManifestBuilder().build(directory.resolve("data.mdb"), model,
                Map.of("vehicleModel","V1","modelYear","2026","region","CN","softwareVersion","1","configurationCode","base"),
                Map.of("pdf",Map.of(),"html",Map.of(),"markdown",Map.of()), hnsw());
        var manifestFile = directory.resolve("manifest.json"); new ManifestWriter().write(manifestFile, manifest);
        assertDoesNotThrow(() -> new com.hirain.aiagent.rag.indexer.pipeline.VerifyPipeline().verifyUsingBundledSchema(directory));
    }

    private static Map<String, Object> hnsw() {
        var result = new java.util.LinkedHashMap<>(KnowledgeStoreContract.hnswManifestDetails());
        result.put("configFingerprint", ManifestBuilderTest.hash());
        return Map.copyOf(result);
    }
}
