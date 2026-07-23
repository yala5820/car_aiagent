package com.hirain.aiagent.rag.indexer.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObjectBoxKnowledgeStoreWriterTest {
    @Test
    void shouldWriteReopenAndVerifyAStagingStore() throws Exception {
        Path staging = Files.createTempDirectory("rag-store-writer-");
        StoreWriteResult result = new ObjectBoxKnowledgeStoreWriter().write(staging, StoreModelValidatorTest.model());

        assertTrue(Files.isRegularFile(staging.resolve("data.mdb")));
        assertEquals(1, result.statistics().documentCount());
        assertEquals(1, result.statistics().parentChunkCount());
        assertEquals(1, result.statistics().childChunkCount());
        assertEquals(1, result.statistics().lexicalTermCount());
    }
}
