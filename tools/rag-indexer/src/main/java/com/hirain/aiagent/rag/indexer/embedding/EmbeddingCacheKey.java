package com.hirain.aiagent.rag.indexer.embedding;

import com.hirain.aiagent.rag.indexer.util.Sha256;

/** 缓存身份绑定 Provider、模型、维度、模板版本和输入文本哈希，绝不包含 API Key。 */
public record EmbeddingCacheKey(String value) {
    public static EmbeddingCacheKey of(String provider, String model, int dimension, int templateVersion, String embeddingText) {
        return new EmbeddingCacheKey(Sha256.ofUtf8(String.join("\u001F", provider, model, String.valueOf(dimension),
                String.valueOf(templateVersion), Sha256.ofUtf8(embeddingText))));
    }
    public static EmbeddingCacheKey of(String provider, String model, int dimension, int templateVersion,
                                       String strategyVersion, String embeddingText) {
        return new EmbeddingCacheKey(Sha256.ofUtf8(String.join("\u001F", provider, model, String.valueOf(dimension),
                String.valueOf(templateVersion), strategyVersion, Sha256.ofUtf8(embeddingText))));
    }
}
