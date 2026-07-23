package com.hirain.aiagent.rag.indexer.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TokenEstimatorTest {
    @Test
    void shouldUseSharedV2RuleAndPreserveV1AuditPath() {
        assertEquals(3, new TokenEstimator().estimate("制动test"));
        assertEquals(3, TokenEstimator.estimateV1ForHistoricalBundle("制动test"));
    }
}
