package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.indexer.embedding.EmbeddingBatchResult;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreFactory;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OfflineDenseEvaluatorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void shouldCalculateDenseRecallAndMrrFromReadOnlyStore() throws Exception {
        Path store = createStore();
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset("v1", "scope", List.of(new RetrievalEvaluationDataset.Case("case-1", "制动液", List.of("child-best"))));
        OfflineDenseEvaluator evaluator = new OfflineDenseEvaluator(requests -> new EmbeddingBatchResult(List.of(vector(1f, 0f))));
        RetrievalMetrics metrics = evaluator.evaluate(store, dataset);
        assertEquals(1d, metrics.recallAt1());
        assertEquals(1d, metrics.meanReciprocalRank());
        assertEquals("child-best", metrics.cases().get(0).rankedChunkIds().get(0));
    }

    @Test
    void shouldRejectDatasetWhoseExpectedChunkIsNotInStore() throws Exception {
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset("v1", "scope", List.of(new RetrievalEvaluationDataset.Case("case-1", "q", List.of("missing"))));
        OfflineDenseEvaluator evaluator = new OfflineDenseEvaluator(requests -> new EmbeddingBatchResult(List.of(vector(1f, 0f))));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(createStore(), dataset));
        assertEquals("EVALUATION_EXPECTED_CHUNK_UNKNOWN", exception.getMessage());
    }

    private Path createStore() throws Exception {
        Path storeDirectory = temporaryDirectory.resolve(java.util.UUID.randomUUID().toString());
        ObjectBoxStoreFactory factory = new ObjectBoxStoreFactory();
        try (var store = factory.createEmpty(storeDirectory)) {
            KnowledgeStoreMetadataEntity metadata = new KnowledgeStoreMetadataEntity(); metadata.knowledgeScopeId = "scope"; store.boxFor(KnowledgeStoreMetadataEntity.class).put(metadata);
            store.boxFor(KnowledgeChunkEntity.class).put(child("child-best", vector(1f, 0f)));
            store.boxFor(KnowledgeChunkEntity.class).put(child("child-other", vector(0f, 1f)));
        }
        return storeDirectory;
    }

    private static KnowledgeChunkEntity child(String id, float[] embedding) { KnowledgeChunkEntity value = new KnowledgeChunkEntity(); value.chunkId = id; value.chunkLevel = "CHILD"; value.embedding = embedding; return value; }
    private static float[] vector(float first, float second) { float[] value = new float[1024]; value[0] = first; value[1] = second; return value; }
}
