package com.hirain.aiagent.rag.indexer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.contract.RagTokenEstimate;
import com.hirain.aiagent.rag.contract.RagTokenEstimator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 离线构建端必须消费共享 Golden，不能维护第二套 Token 计数口径。 */
final class RagTokenEstimatorGoldenTest {
    @Test
    void shouldMatchSharedTokenEstimatorV2Golden() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("..", "..", "rag-schema", "test-vectors", "token-estimator-v2.json").toFile());
        RagTokenEstimator estimator = new RagTokenEstimator();
        assertEquals(RagTokenEstimator.VERSION, root.path("algorithmVersion").asText());
        for (JsonNode item : root.path("cases")) {
            RagTokenEstimate actual = estimator.estimate(item.path("input").asText());
            JsonNode expected = item.path("expected");
            assertEquals(expected.path("total").asInt(), actual.totalTokens(), item.path("id").asText());
            assertEquals(expected.path("cjk").asInt(), actual.cjkTokens(), item.path("id").asText());
            assertEquals(expected.path("latinAndDigit").asInt(), actual.latinAndDigitTokens(), item.path("id").asText());
            assertEquals(expected.path("punctuation").asInt(), actual.punctuationTokens(), item.path("id").asText());
            assertEquals(expected.path("newline").asInt(), actual.newlineTokens(), item.path("id").asText());
            assertEquals(expected.path("other").asInt(), actual.otherTokens(), item.path("id").asText());
        }
    }
}
