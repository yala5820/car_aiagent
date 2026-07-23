package com.hirain.aiagent.rag.indexer.embedding;

import java.util.List;

/** 单 Batch 结果按原输入顺序保存，不能根据服务端偶然返回顺序写库。 */
public record EmbeddingBatchResult(List<float[]> vectors) {
    public EmbeddingBatchResult {
        vectors = List.copyOf(vectors);
    }
}
