package com.hirain.aiagent.rag.retrieval;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 文档 Embedding 模板版本和正文渲染必须与离线构建端共享 Golden 一致。 */
public class EmbeddingInputGoldenTest {
    @Test public void matchesSharedEmbeddingInputGolden() throws Exception {
        String json = new String(Files.readAllBytes(Path.of("..", "rag-schema", "test-vectors", "embedding-input-v2.json")), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); assertEquals(EmbeddingInputRenderer.TEMPLATE_VERSION, root.get("version").getAsInt());
        JsonObject value = root.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals(value.get("expected").getAsString(), new EmbeddingInputRenderer().render(value.get("documentTitle").getAsString(), value.get("headingPath").getAsString(), value.get("chunkType").getAsString(), value.get("content").getAsString()));
    }
}
