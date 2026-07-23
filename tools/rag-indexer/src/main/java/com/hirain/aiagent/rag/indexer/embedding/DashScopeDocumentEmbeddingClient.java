package com.hirain.aiagent.rag.indexer.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** DashScope OpenAI 兼容 Embedding 客户端；Key 由统一本地 Provider 读取，不进入日志、异常或缓存。 */
public final class DashScopeDocumentEmbeddingClient implements DocumentEmbeddingClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final String endpoint;
    private final java.util.function.Supplier<String> apiKeySupplier;

    public DashScopeDocumentEmbeddingClient() {
        this(new OkHttpClient(), "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", DashScopeApiKeyProvider::load);
    }

    public DashScopeDocumentEmbeddingClient(OkHttpClient client, String endpoint) {
        this(client, endpoint, DashScopeApiKeyProvider::load);
    }

    public DashScopeDocumentEmbeddingClient(OkHttpClient client, String endpoint, java.util.function.Supplier<String> apiKeySupplier) {
        this.client = client;
        this.endpoint = endpoint;
        this.apiKeySupplier = apiKeySupplier;
    }

    @Override
    public EmbeddingBatchResult embed(List<EmbeddingRequest> requests) throws EmbeddingException {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) throw new EmbeddingException("DASHSCOPE_API_KEY_MISSING", false);
        try {
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.createObjectNode(); root.put("model", "text-embedding-v4");
            var input = root.putArray("input"); for (EmbeddingRequest request : requests) input.add(request.embeddingText());
            Request request = new Request.Builder().url(endpoint).header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(mapper.writeValueAsBytes(root), JSON)).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new EmbeddingException("DASHSCOPE_HTTP_" + response.code(), response.code() == 429 || response.code() >= 500);
                JsonNode data = mapper.readTree(response.body().bytes()).path("data");
                if (!data.isArray() || data.size() != requests.size()) {
                    throw new EmbeddingException("DASHSCOPE_RESPONSE_SIZE_INVALID", false);
                }
                // OpenAI 兼容接口允许 data 数组不按请求顺序返回，必须依据 index 回填，避免向量错配到其他 Child。
                List<float[]> vectors = new ArrayList<>(Collections.nCopies(requests.size(), null));
                for (JsonNode item : data) {
                    JsonNode responseIndex = item.path("index");
                    if (!responseIndex.canConvertToInt()) {
                        throw new EmbeddingException("DASHSCOPE_RESPONSE_INDEX_INVALID", false);
                    }
                    int requestIndex = responseIndex.asInt();
                    if (requestIndex < 0 || requestIndex >= requests.size() || vectors.get(requestIndex) != null) {
                        throw new EmbeddingException("DASHSCOPE_RESPONSE_INDEX_INVALID", false);
                    }
                    JsonNode embedding = item.path("embedding"); float[] vector = new float[embedding.size()];
                    for (int index = 0; index < vector.length; index++) vector[index] = (float) embedding.get(index).asDouble();
                    vectors.set(requestIndex, vector);
                }
                if (vectors.stream().anyMatch(vector -> vector == null)) {
                    throw new EmbeddingException("DASHSCOPE_RESPONSE_INDEX_INVALID", false);
                }
                return new EmbeddingBatchResult(vectors);
            }
        } catch (EmbeddingException exception) { throw exception; }
        catch (Exception exception) { throw new EmbeddingException("DASHSCOPE_TRANSPORT_FAILURE", true); }
    }
}
