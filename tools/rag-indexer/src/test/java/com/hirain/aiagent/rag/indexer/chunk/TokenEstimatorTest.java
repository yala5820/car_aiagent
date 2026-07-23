package com.hirain.aiagent.rag.indexer.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TokenEstimatorTest {
    @Test
    void shouldUseVersionedCjkAndLatinEstimationRule() {
        assertEquals(3, new TokenEstimator().estimate("制动test"));
    }
}
