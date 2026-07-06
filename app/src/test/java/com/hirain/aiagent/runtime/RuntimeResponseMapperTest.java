package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentResponse;

import org.junit.Test;

public class RuntimeResponseMapperTest {

    @Test
    public void toAgentResponse_mapsSuccess() {
        RuntimeResult success = RuntimeResult.success("req-1", "session-1", "好的", 2000L, 1, 10L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(success);

        assertEquals("req-1", response.getRequestId());
        assertEquals("session-1", response.getSessionId());
        assertTrue(response.isSuccess());
        assertEquals("好的", response.getText());
        assertNull(response.getErrorType());
        assertEquals(2000L, response.getTimestamp());
    }

    @Test
    public void toAgentResponse_mapsException() {
        RuntimeResult exception = RuntimeResult.failure("req-1", null, "EXCEPTION", "boom", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(exception);

        assertEquals(false, response.isSuccess());
        assertNull(response.getSessionId());
        assertEquals("系统: 请求失败 - boom", response.getText());
        assertEquals("EXCEPTION", response.getErrorType());
    }

    @Test
    public void toAgentResponse_mapsTimeout() {
        RuntimeResult timeout = RuntimeResult.timeout("req-1", "session-1", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(timeout);

        assertEquals("系统: 请求超时", response.getText());
        assertEquals("TIMEOUT", response.getErrorType());
    }

    @Test
    public void toAgentResponse_mapsGenericFailure() {
        RuntimeResult failure = RuntimeResult.failure("req-1", "session-1", "UNKNOWN", "出错了", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(failure);

        assertEquals("出错了", response.getText());
        assertEquals("UNKNOWN", response.getErrorType());
    }
}
