package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.cli.CliArguments;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BuildPipelineTest {
    @Test void shouldRunAllPhasesInFixedOrder() {
        var phases = new ArrayList<BuildPhase>(); var token = new BuildCancellationToken();
        var context = new BuildExecutionContext("run", Instant.EPOCH, new CliArguments("validate", null, null, null, null, null, null, null), token);
        new BuildPipeline().execute(context, (phase, ignored) -> { }, phases::add);
        assertEquals(phases, java.util.List.of(BuildPhase.VALIDATED, BuildPhase.PARSED, BuildPhase.CHUNKED, BuildPhase.EMBEDDED, BuildPhase.INDEXED, BuildPhase.STORE_WRITTEN, BuildPhase.VERIFIED));
    }
    @Test void shouldStopBeforeAnyPhaseWhenCancelled() {
        var phases = new ArrayList<BuildPhase>(); var token = new BuildCancellationToken(); token.cancel();
        var context = new BuildExecutionContext("run", Instant.EPOCH, new CliArguments("validate", null, null, null, null, null, null, null), token);
        assertThrows(BuildCancellationToken.BuildCancelledException.class,
                () -> new BuildPipeline().execute(context, (phase, ignored) -> { }, phases::add));
        assertEquals(java.util.List.of(), phases);
    }
}
