package com.hirain.aiagent.rag.indexer.lexical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LexicalIndexBuilderTest {
    @Test
    void shouldBuildChildOnlyTfDfAndAverageLengthInStableOrder() {
        LexicalAnalyzerConfig config = LexicalAnalyzerConfig.v1();
        LexicalIndex index = new LexicalIndexBuilder(new CjkLatinLexicalAnalyzer(config), config).build(List.of(
                new LexicalDocument("child-b", "acc acc"), new LexicalDocument("child-a", "acc")));

        assertEquals(List.of("child-a", "child-b"), index.documentLengths().keySet().stream().toList());
        assertEquals(2, index.documentFrequency("acc"));
        assertEquals(1, index.postingsByTerm().get("acc").get(0).termFrequency());
        assertEquals(2, index.postingsByTerm().get("acc").get(1).termFrequency());
        assertEquals(1.5D, index.averageDocumentLength());
    }
}
