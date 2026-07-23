package com.hirain.aiagent.rag.indexer.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublishabilityEvaluatorTest {
    @Test
    void shouldOnlyAllowEveryHardGateToPass() {
        PublishabilityEvaluator evaluator = new PublishabilityEvaluator();
        assertTrue(evaluator.evaluate(List.of(), true, true, true, true));
        assertFalse(evaluator.evaluate(List.of("EMBEDDING_MISSING"), true, true, true, true));
        assertFalse(evaluator.evaluate(List.of(), false, true, true, true));
        assertFalse(evaluator.evaluate(List.of(), true, false, true, true));
    }
}
