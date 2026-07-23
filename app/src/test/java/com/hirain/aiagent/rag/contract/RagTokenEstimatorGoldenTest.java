package com.hirain.aiagent.rag.contract;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Android 运行端与离线端必须消费同一 TokenEstimator V2 Golden。 */
public class RagTokenEstimatorGoldenTest {
    @Test
    public void matchesSharedTokenEstimatorV2Golden() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-vectors", "token-estimator-v2.json")), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RagTokenEstimator estimator = new RagTokenEstimator();
        assertEquals(RagTokenEstimator.VERSION, root.get("algorithmVersion").getAsString());
        for (var item : root.getAsJsonArray("cases")) {
            JsonObject value = item.getAsJsonObject();
            JsonObject expected = value.getAsJsonObject("expected");
            RagTokenEstimate actual = estimator.estimate(value.get("input").getAsString());
            assertEquals(value.get("id").getAsString(), expected.get("total").getAsInt(), actual.totalTokens());
            assertEquals(value.get("id").getAsString(), expected.get("cjk").getAsInt(), actual.cjkTokens());
            assertEquals(value.get("id").getAsString(), expected.get("latinAndDigit").getAsInt(), actual.latinAndDigitTokens());
            assertEquals(value.get("id").getAsString(), expected.get("punctuation").getAsInt(), actual.punctuationTokens());
            assertEquals(value.get("id").getAsString(), expected.get("newline").getAsInt(), actual.newlineTokens());
            assertEquals(value.get("id").getAsString(), expected.get("other").getAsInt(), actual.otherTokens());
        }
    }
}
