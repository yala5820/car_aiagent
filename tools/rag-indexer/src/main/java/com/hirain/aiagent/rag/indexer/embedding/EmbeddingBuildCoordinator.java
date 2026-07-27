package com.hirain.aiagent.rag.indexer.embedding;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.chunk.EmbeddingTextRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 串行协调 Embedding：先读缓存，后按稳定批次请求；任一 Child 失败即整体失败，禁止不完整发布。 */
public final class EmbeddingBuildCoordinator {
    private final DocumentEmbeddingClient client;
    private final EmbeddingCache cache;
    private final int batchSize;
    private final int maxRetries;
    private final String strategyVersion;

    public EmbeddingBuildCoordinator(DocumentEmbeddingClient client, EmbeddingCache cache, int batchSize, int maxRetries) {
        this(client, cache, batchSize, maxRetries, "child-embedding-v1");
    }

    public EmbeddingBuildCoordinator(DocumentEmbeddingClient client, EmbeddingCache cache, int batchSize, int maxRetries,
                                     String strategyVersion) {
        this.client = client;
        this.cache = cache;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.strategyVersion = strategyVersion == null || strategyVersion.isBlank() ? "child-embedding-v1" : strategyVersion;
    }

    public Map<String, float[]> embed(List<EmbeddingRequest> requests, BuildCancellationToken cancellationToken) throws EmbeddingException {
        Map<String, float[]> output = new LinkedHashMap<>();
        List<EmbeddingRequest> misses = new java.util.ArrayList<>();
        for (EmbeddingRequest request : requests) {
            cancellationToken.throwIfCancelled();
            EmbeddingCacheKey key = key(request);
            var cached = cache.get(key);
            if (cached.isPresent()) output.put(request.childId(), cached.get()); else misses.add(request);
        }
        for (List<EmbeddingRequest> batch : new EmbeddingBatchPlanner().plan(misses, batchSize)) {
            cancellationToken.throwIfCancelled();
            EmbeddingBatchResult result = invokeWithRetry(batch, cancellationToken);
            if (result.vectors().size() != batch.size()) throw new EmbeddingException("EMBEDDING_RESPONSE_COUNT_INVALID", false);
            for (int index = 0; index < batch.size(); index++) {
                cancellationToken.throwIfCancelled();
                float[] vector = result.vectors().get(index);
                new EmbeddingVectorValidator().validate(vector);
                EmbeddingRequest request = batch.get(index);
                cache.put(key(request), vector);
                output.put(request.childId(), vector);
            }
        }
        if (output.size() != requests.size()) throw new EmbeddingException("EMBEDDING_RESULT_INCOMPLETE", false);
        return Map.copyOf(output);
    }

    private EmbeddingBatchResult invokeWithRetry(List<EmbeddingRequest> batch, BuildCancellationToken token) throws EmbeddingException {
        EmbeddingRetryPolicy retry = new EmbeddingRetryPolicy();
        for (int attempt = 0; ; attempt++) {
            token.throwIfCancelled();
            try { return client.embed(batch); }
            catch (EmbeddingException exception) {
                if (!retry.shouldRetry(exception, attempt, maxRetries)) throw exception;
            }
        }
    }

    private EmbeddingCacheKey key(EmbeddingRequest request) {
        return EmbeddingCacheKey.of("DashScope", "text-embedding-v4", 1024, EmbeddingTextRenderer.TEMPLATE_VERSION, strategyVersion, request.embeddingText());
    }
}
