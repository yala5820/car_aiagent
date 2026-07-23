package com.hirain.aiagent.rag.indexer.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EmbeddingBatchPlannerTest {
    @Test
    void shouldKeepInputOrderAcrossBatches() {
        List<EmbeddingRequest> requests = List.of(request(0), request(1), request(2));
        var batches = new EmbeddingBatchPlanner().plan(requests, 2);

        assertEquals(List.of(0, 1), batches.get(0).stream().map(EmbeddingRequest::inputIndex).toList());
        assertEquals(List.of(2), batches.get(1).stream().map(EmbeddingRequest::inputIndex).toList());
    }

    private EmbeddingRequest request(int index) { return new EmbeddingRequest(index, "child-" + index, "text"); }
}
