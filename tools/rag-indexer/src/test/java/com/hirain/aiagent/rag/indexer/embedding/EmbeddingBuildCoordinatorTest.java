package com.hirain.aiagent.rag.indexer.embedding;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EmbeddingBuildCoordinatorTest {
    @TempDir Path directory;

    @Test
    void shouldMapVectorsBackToInputOrderAndReuseCache() throws Exception {
        int[] calls = {0};
        DocumentEmbeddingClient client = requests -> { calls[0]++; return new EmbeddingBatchResult(requests.stream().map(request -> vector(request.inputIndex())).toList()); };
        EmbeddingBuildCoordinator coordinator = new EmbeddingBuildCoordinator(client, new FileEmbeddingCache(directory), 2, 1);
        List<EmbeddingRequest> requests = List.of(new EmbeddingRequest(0, "a", "one"), new EmbeddingRequest(1, "b", "two"));

        assertEquals(2, coordinator.embed(requests, new BuildCancellationToken()).size());
        assertEquals(2, coordinator.embed(requests, new BuildCancellationToken()).size());
        assertEquals(1, calls[0]);
    }

    @Test
    void shouldStopBeforeStartingAnyRequestWhenCancelled() {
        DocumentEmbeddingClient client = requests -> { throw new AssertionError("must not call"); };
        BuildCancellationToken token = new BuildCancellationToken(); token.cancel();
        EmbeddingBuildCoordinator coordinator = new EmbeddingBuildCoordinator(client, new FileEmbeddingCache(directory), 1, 0);

        assertThrows(BuildCancellationToken.BuildCancelledException.class,
                () -> coordinator.embed(List.of(new EmbeddingRequest(0, "a", "one")), token));
    }

    private float[] vector(int first) { float[] result = new float[1024]; result[0] = first; return result; }
}
