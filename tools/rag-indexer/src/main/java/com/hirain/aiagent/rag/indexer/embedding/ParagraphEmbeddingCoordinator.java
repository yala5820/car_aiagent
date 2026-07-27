package com.hirain.aiagent.rag.indexer.embedding;

import com.hirain.aiagent.rag.indexer.chunk.ParagraphEmbeddingProvider;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 复用现有 DashScope/缓存/重试链路，专门为 Parent 内短 Paragraph 提供向量。 */
public final class ParagraphEmbeddingCoordinator implements ParagraphEmbeddingProvider {
    private final EmbeddingBuildCoordinator coordinator;

    public ParagraphEmbeddingCoordinator(DocumentEmbeddingClient client, EmbeddingCache cache, int batchSize, int maxRetries,
                                          String strategyVersion) {
        this.coordinator = new EmbeddingBuildCoordinator(client, cache, batchSize, maxRetries,
                strategyVersion == null ? "paragraph-embedding-v1" : strategyVersion);
    }

    @Override
    public Map<String, float[]> embed(List<ParagraphEmbeddingRequest> requests, BuildCancellationToken token) {
        try {
            List<EmbeddingRequest> adapted = java.util.stream.IntStream.range(0, requests.size())
                    .mapToObj(index -> { ParagraphEmbeddingRequest request = requests.get(index);
                        return new EmbeddingRequest(index, request.paragraphId(), request.embeddingText()); }).toList();
            return coordinator.embed(adapted, token);
        } catch (EmbeddingException exception) {
            // 不携带底层正文或 API Key；由正式构建入口统一转为受控 EMBEDDING_ERROR。
            throw new ParagraphEmbeddingUnavailableException(exception.getMessage());
        }
    }

    public static final class ParagraphEmbeddingUnavailableException extends RuntimeException {
        public ParagraphEmbeddingUnavailableException(String reason) { super(reason == null ? "PARAGRAPH_EMBEDDING_FAILED" : reason); }
    }
}
