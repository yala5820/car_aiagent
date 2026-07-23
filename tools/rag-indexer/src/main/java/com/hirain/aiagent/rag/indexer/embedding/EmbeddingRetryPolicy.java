package com.hirain.aiagent.rag.indexer.embedding;

/** 仅瞬时错误可重试；认证、参数与维度错误不得重试。 */
public final class EmbeddingRetryPolicy {
    public boolean shouldRetry(EmbeddingException exception, int attempt, int maxRetries) {
        return exception.retryable() && attempt < maxRetries;
    }
}
