package com.hirain.aiagent.rag.indexer.lexical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TermFrequencyCounterTest {
    @Test
    void shouldCountAndSortTermsDeterministically() {
        assertEquals(Map.of("acc", 2, "esp", 1), new TermFrequencyCounter().count(List.of("esp", "acc", "acc")));
    }
}
