package com.hirain.aiagent.rag.indexer.lexical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CjkLatinLexicalAnalyzerTest {
    private final LexicalAnalyzer analyzer = new CjkLatinLexicalAnalyzer(LexicalAnalyzerConfig.v1());

    @Test
    void shouldKeepCjkNgramsCodesAndUppercasePhraseSemantics() {
        assertEquals(List.of("制动", "动液", "制动液", "acc", "auto", "hold", "p0a1", "v1.2.3", "acc auto hold"),
                analyzer.analyze("制动液 ACC AUTO HOLD P0A1 V1.2.3"));
    }
}
