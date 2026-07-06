package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.core.AgentResult;

import org.junit.Test;

import java.util.List;

public class RuntimeResultTest {

    @Test
    public void fromAgentResult_mapsSuccess() {
        RuntimeResult result = RuntimeResult.fromAgentResult(
                "req-1", "session-1",
                AgentResult.success("好的", 2, 30L, List.of()),
                2000L);

        assertTrue(result.success());
        assertEquals("好的", result.output());
        assertEquals(2, result.iterationsUsed());
        assertEquals(30L, result.durationMs());
        assertEquals(2000L, result.timestampMs());
    }

    @Test
    public void fromAgentResult_mapsError() {
        RuntimeResult result = RuntimeResult.fromAgentResult(
                "req-1", null,
                AgentResult.error(AgentResult.ErrorType.MODEL_CALL_FAILED, "模型失败"),
                2000L);

        assertEquals(false, result.success());
        assertEquals("MODEL_CALL_FAILED", result.errorType());
        assertEquals("模型失败", result.errorDetail());
        assertNull(result.sessionId());
    }

    @Test
    public void fromException_mapsToExceptionType() {
        RuntimeResult result = RuntimeResult.fromException(
                "req-1", "session-1", new IllegalStateException("boom"), 2000L);

        assertEquals("EXCEPTION", result.errorType());
        assertEquals("boom", result.errorDetail());
        assertEquals(2000L, result.timestampMs());
    }

    @Test
    public void timeout_createsTimeoutResult() {
        RuntimeResult result = RuntimeResult.timeout("req-1", "session-1", 2000L);

        assertEquals("TIMEOUT", result.errorType());
        assertEquals("请求超时", result.errorDetail());
        assertEquals(2000L, result.timestampMs());
    }
}
