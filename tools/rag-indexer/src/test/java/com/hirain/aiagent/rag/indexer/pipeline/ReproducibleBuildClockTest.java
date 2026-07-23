package com.hirain.aiagent.rag.indexer.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ReproducibleBuildClockTest {
    @Test
    void shouldAlwaysProduceAnInstant() {
        assertNotNull(new ReproducibleBuildClock().now());
    }
}
