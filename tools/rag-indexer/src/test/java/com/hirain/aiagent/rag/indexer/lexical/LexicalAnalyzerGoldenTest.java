package com.hirain.aiagent.rag.indexer.lexical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LexicalAnalyzerGoldenTest {
    @Test
    void shouldMatchSharedGolden() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of("..", "..", "rag-schema", "test-vectors", "lexical-analyzer-v1.json")));
        assertEquals(LexicalAnalyzerConfig.v1().version(), root.path("version").asText());
        LexicalAnalyzer analyzer = new CjkLatinLexicalAnalyzer(LexicalAnalyzerConfig.v1());
        for (JsonNode testCase : root.path("cases")) {
            List<String> expected = new ArrayList<>();
            testCase.path("expectedTerms").forEach(term -> expected.add(term.asText()));
            assertEquals(expected, analyzer.analyze(testCase.path("input").asText()), testCase.path("id").asText());
        }
    }
}
