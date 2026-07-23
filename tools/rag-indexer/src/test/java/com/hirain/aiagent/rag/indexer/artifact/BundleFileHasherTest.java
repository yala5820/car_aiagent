package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BundleFileHasherTest {
    @Test
    void shouldCalculateStableStreamingSha256AndSize() throws Exception {
        var file = Files.createTempFile("rag-hash-", ".bin");
        Files.writeString(file, "abc");
        var hash = new BundleFileHasher().hash(file);
        assertEquals(3, hash.sizeBytes());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.sha256());
    }
}
