package com.hirain.aiagent.rag.indexer.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EmbeddingInputGoldenTest {
    @Test
    void shouldMatchSharedEmbeddingInputGolden() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("..", "..", "rag-schema", "test-vectors", "embedding-input-v1.json").toFile());
        JsonNode item = root.path("cases").get(0);

        String actual = new EmbeddingTextRenderer().render(item.path("documentTitle").asText(), item.path("headingPath").asText(),
                item.path("chunkType").asText(), item.path("content").asText());

        assertEquals(EmbeddingTextRenderer.TEMPLATE_VERSION, root.path("version").asInt());
        assertEquals(item.path("expected").asText(), actual);
    }
}
