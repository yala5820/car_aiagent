package com.hirain.aiagent.rag.indexer.pipeline;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

final class BuildCheckpointStoreTest {
    @Test void shouldRecoverOnlyWhenAllFingerprintsMatch() throws Exception {
        var run = new BuildWorkspace().create(Files.createTempDirectory("rag-work-"), "run_1");
        var expected = new BuildCheckpoint(BuildPhase.PARSED, "schema", "parser", "chunk", "template");
        var store = new BuildCheckpointStore(); store.save(run, expected);
        assertEquals(expected, store.loadMatching(run, expected));
        assertNull(store.loadMatching(run, new BuildCheckpoint(BuildPhase.PARSED, "changed", "parser", "chunk", "template")));
        assertThrows(IllegalArgumentException.class, () -> new BuildWorkspace().create(run.getParent().getParent(), "run_1"));
    }
}
