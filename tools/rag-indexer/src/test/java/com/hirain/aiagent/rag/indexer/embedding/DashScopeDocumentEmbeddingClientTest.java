package com.hirain.aiagent.rag.indexer.embedding;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DashScopeDocumentEmbeddingClientTest {
    @Test
    void shouldFailBeforeNetworkWhenApiKeyIsMissing() {
        DashScopeDocumentEmbeddingClient client = new DashScopeDocumentEmbeddingClient(new OkHttpClient(), "http://127.0.0.1:1", () -> "");

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.embed(List.of(new EmbeddingRequest(0, "child", "text"))));

        assertEquals("DASHSCOPE_API_KEY_MISSING", exception.getMessage());
    }

    @Test
    void shouldMapMockOpenAiCompatibleResponseWithoutExposingKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            StringBuilder vector = new StringBuilder();
            for (int index = 0; index < 1024; index++) {
                if (index > 0) vector.append(',');
                vector.append(index == 0 ? "0.5" : "0");
            }
            byte[] body = ("{\"data\":[{\"index\":0,\"embedding\":[" + vector + "]}]}" ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DashScopeDocumentEmbeddingClient client = new DashScopeDocumentEmbeddingClient(new OkHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings", () -> "test-key");
            float[] vector = client.embed(List.of(new EmbeddingRequest(0, "child", "text"))).vectors().get(0);

            assertEquals(1024, vector.length);
            assertEquals(0.5f, vector[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRestoreRequestOrderFromResponseIndexes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] body = ("{\"data\":[{\"index\":1,\"embedding\":[2.0]},{\"index\":0,\"embedding\":[1.0]}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DashScopeDocumentEmbeddingClient client = new DashScopeDocumentEmbeddingClient(new OkHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings", () -> "test-key");
            List<float[]> vectors = client.embed(List.of(
                    new EmbeddingRequest(0, "child-0", "first"),
                    new EmbeddingRequest(1, "child-1", "second"))).vectors();

            assertEquals(1.0f, vectors.get(0)[0]);
            assertEquals(2.0f, vectors.get(1)[0]);
        } finally {
            server.stop(0);
        }
    }
}
