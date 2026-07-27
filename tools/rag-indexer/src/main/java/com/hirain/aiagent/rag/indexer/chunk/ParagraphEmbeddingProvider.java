package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.embedding.ParagraphEmbeddingRequest;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import java.util.List;
import java.util.Map;

/** Child 规划所需的短 Paragraph 向量边界；正式实现失败时必须 fail-closed。 */
public interface ParagraphEmbeddingProvider {
    Map<String, float[]> embed(List<ParagraphEmbeddingRequest> requests, BuildCancellationToken token);
}
