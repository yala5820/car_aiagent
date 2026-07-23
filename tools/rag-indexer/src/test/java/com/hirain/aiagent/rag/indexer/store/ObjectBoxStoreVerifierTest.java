package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class ObjectBoxStoreVerifierTest {
    @Test
    void shouldRejectMetadataCountMismatchAfterReopen() throws Exception {
        Path staging = Files.createTempDirectory("rag-store-corrupt-");
        new ObjectBoxKnowledgeStoreWriter().write(staging, StoreModelValidatorTest.model());
        try (var store = MyObjectBox.builder().directory(staging.toFile()).build()) {
            KnowledgeStoreMetadataEntity metadata = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll().get(0);
            metadata.childChunkCount = 2;
            store.boxFor(KnowledgeStoreMetadataEntity.class).put(metadata);
        }
        assertThrows(IllegalStateException.class, () -> new ObjectBoxStoreVerifier().verify(staging));
    }
}
