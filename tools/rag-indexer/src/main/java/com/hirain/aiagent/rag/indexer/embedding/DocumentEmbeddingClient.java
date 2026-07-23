package com.hirain.aiagent.rag.indexer.embedding;

import java.util.List;

/** 云端 Embedding 边界；实现不得泄露 API Key 或完整正文到异常消息。 */
public interface DocumentEmbeddingClient {
    EmbeddingBatchResult embed(List<EmbeddingRequest> requests) throws EmbeddingException;
}
