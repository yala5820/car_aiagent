package com.hirain.aiagent.rag.indexer.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class ObjectBoxCorruptStoreTest {
    @Test
    void shouldRejectNonEmptyStagingDirectoryInsteadOfReusingStore() throws Exception {
        var staging = Files.createTempDirectory("rag-store-nonempty-");
        Files.writeString(staging.resolve("unexpected.txt"), "not-a-store");
        assertThrows(IllegalArgumentException.class, () -> new ObjectBoxKnowledgeStoreWriter().write(staging, StoreModelValidatorTest.model()));
    }
}
