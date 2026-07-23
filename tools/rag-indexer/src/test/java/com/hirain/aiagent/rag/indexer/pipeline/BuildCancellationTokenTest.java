package com.hirain.aiagent.rag.indexer.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保后续 Pipeline 可统一感知 Shutdown Hook 与线程中断传来的取消信号。 */
final class BuildCancellationTokenTest {

    @Test
    void shouldExposeExplicitCancellationToPipelineBoundaries() {
        BuildCancellationToken token = new BuildCancellationToken();
        assertFalse(token.isCancelled());
        token.cancel();
        assertTrue(token.isCancelled());
        assertThrows(BuildCancellationToken.BuildCancelledException.class, token::throwIfCancelled);
    }
}
