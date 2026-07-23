package com.hirain.aiagent.rag.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 直接读取离线唯一 Golden，禁止 Android 自行录入同名分词期望。 */
public class LexicalAnalyzerGoldenTest {
    @Test public void matchesSharedLexicalAnalyzerGolden() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-vectors", "lexical-analyzer-v1.json")), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); assertEquals("lexical-analyzer-v1", root.get("version").getAsString());
        LexicalAnalyzer analyzer = new CjkLatinLexicalAnalyzer();
        for (var item : root.getAsJsonArray("cases")) { JsonObject value = item.getAsJsonObject(); assertEquals(expected(value.getAsJsonArray("expectedTerms")), analyzer.analyze(value.get("input").getAsString())); }
    }
    private static List<String> expected(JsonArray source) { List<String> values = new ArrayList<>(); for (var item : source) values.add(item.getAsString()); return values; }
}
