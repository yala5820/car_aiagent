package com.hirain.aiagent.rag.indexer.embedding;

/** 对调用方只暴露稳定错误类别和是否可重试。 */
public final class EmbeddingException extends Exception {
    private final boolean retryable;

    public EmbeddingException(String reasonCode, boolean retryable) {
        super(reasonCode);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
