package com.hirain.aiagent.rag.indexer.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmbeddingRetryPolicyTest {
    @Test
    void shouldRetryOnlyTransientFailuresWithinLimit() {
        EmbeddingRetryPolicy policy = new EmbeddingRetryPolicy();
        assertTrue(policy.shouldRetry(new EmbeddingException("HTTP_429", true), 0, 2));
        assertFalse(policy.shouldRetry(new EmbeddingException("AUTH", false), 0, 2));
        assertFalse(policy.shouldRetry(new EmbeddingException("HTTP_429", true), 2, 2));
    }
}
