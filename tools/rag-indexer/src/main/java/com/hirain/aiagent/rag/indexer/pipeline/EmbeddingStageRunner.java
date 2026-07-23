package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.embedding.EmbeddingBuildCoordinator;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingRequest;

import java.util.List;

/** Child 请求与向量结果必须一一对应；任何缺失都阻止后续 Lexical/Store 阶段。 */
public final class EmbeddingStageRunner {
    private final EmbeddingBuildCoordinator coordinator;
    public EmbeddingStageRunner(EmbeddingBuildCoordinator coordinator) { this.coordinator = coordinator; }
    public DocumentBuildState embed(DocumentBuildState state, List<EmbeddingRequest> requests, BuildCancellationToken token) throws EmbeddingException {
        if (state.chunkResult() == null || state.chunkResult().children().size() != requests.size()) throw new IllegalArgumentException("Child 与 Embedding 请求数量不一致");
        var vectors = coordinator.embed(requests, token);
        if (vectors.size() != state.chunkResult().children().size()) throw new IllegalStateException("Child Embedding 不完整");
        return state.withEmbeddings(vectors);
    }
}
