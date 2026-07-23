package com.hirain.aiagent.rag.indexer.parser.table;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TableNormalizerTest {
    @Test
    void shouldNormalizeWhitespaceWithoutChangingColumns() {
        assertEquals(List.of(List.of("制动 液", "3 L")),
                new TableNormalizer().normalize(List.of(List.of(" 制动\n液 ", " 3   L "))));
    }
}
