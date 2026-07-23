package com.hirain.aiagent.rag.indexer.embedding;

import java.util.ArrayList;
import java.util.List;

/** 固定顺序批次规划，不依赖 HashMap 遍历；真实接口限额获准后可只调整 maxBatchSize。 */
public final class EmbeddingBatchPlanner {
    public List<List<EmbeddingRequest>> plan(List<EmbeddingRequest> requests, int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("EMBEDDING_BATCH_SIZE_INVALID");
        }
        List<List<EmbeddingRequest>> output = new ArrayList<>();
        for (int index = 0; index < requests.size(); index += maxBatchSize) {
            output.add(List.copyOf(requests.subList(index, Math.min(requests.size(), index + maxBatchSize))));
        }
        return List.copyOf(output);
    }
}
