package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DeterministicEntityWriterTest {
    @Test
    void shouldMapPostingToCurrentStoreChildLongId() throws Exception {
        Path staging = Files.createTempDirectory("rag-store-order-");
        new ObjectBoxKnowledgeStoreWriter().write(staging, StoreModelValidatorTest.model());
        try (var store = MyObjectBox.builder().directory(staging.toFile()).build()) {
            KnowledgeChunkEntity child = store.boxFor(KnowledgeChunkEntity.class).getAll().stream()
                    .filter(chunk -> "CHILD".equals(chunk.chunkLevel)).findFirst().orElseThrow();
            LexicalTermEntity term = store.boxFor(LexicalTermEntity.class).getAll().get(0);
            assertEquals(child.id, term.chunkEntityIds[0]);
            assertEquals(1, term.termFrequencies[0]);
        }
    }
}
