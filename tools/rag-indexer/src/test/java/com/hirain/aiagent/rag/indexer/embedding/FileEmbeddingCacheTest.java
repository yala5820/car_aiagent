package com.hirain.aiagent.rag.indexer.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileEmbeddingCacheTest {
    @TempDir
    Path directory;

    @Test
    void shouldRoundTripValidatedVectorAndIgnoreMissingEntry() {
        FileEmbeddingCache cache = new FileEmbeddingCache(directory);
        EmbeddingCacheKey key = EmbeddingCacheKey.of("DashScope", "text-embedding-v4", 1024, 1, "text");
        float[] vector = new float[1024]; vector[0] = 0.5f;

        assertTrue(cache.get(key).isEmpty());
        cache.put(key, vector);
        assertArrayEquals(vector, cache.get(key).orElseThrow());
    }
}
