package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentResponse;

import org.junit.Test;

public class RuntimeResponseMapperTest {

    @Test
    public void toAgentResponse_mapsSuccess() {
        RuntimeResult success = RuntimeResult.success("req-1", "session-1", "user-A", "chat", "client-1",
                "好的", 2000L, 1, 10L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(success);

        assertEquals("req-1", response.getRequestId());
        assertEquals("session-1", response.getSessionId());
        assertEquals("user-A", response.getUserId());
        assertEquals("chat", response.getPersonaId());
        assertEquals("client-1", response.getClientMessageId());
        assertTrue(response.isSuccess());
        assertEquals("好的", response.getText());
        assertNull(response.getErrorType());
        assertEquals("SUCCESS", response.getStatus());
        assertEquals(2000L, response.getTimestamp());
    }

    @Test
    public void toAgentResponse_mapsException() {
        RuntimeResult exception = RuntimeResult.failure("req-1", null, "user-A", "chat", "client-1",
                "EXCEPTION", "boom", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(exception);

        assertEquals(false, response.isSuccess());
        assertNull(response.getSessionId());
        assertEquals("系统: 请求暂时无法完成，请稍后重试。", response.getText());
        assertEquals("EXCEPTION", response.getErrorType());
        assertEquals("EXCEPTION", response.getStatus());
    }

    @Test
    public void toAgentResponse_mapsTimeout() {
        RuntimeResult timeout = RuntimeResult.timeout("req-1", "session-1", "user-A", "chat", "client-1", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(timeout);

        assertEquals("系统: 请求超时", response.getText());
        assertEquals("TIMEOUT", response.getErrorType());
        assertEquals("TIMEOUT", response.getStatus());
    }

    @Test
    public void toAgentResponse_mapsGenericFailure() {
        RuntimeResult failure = RuntimeResult.failure("req-1", "session-1", null, null, null,
                "UNKNOWN", "出错了", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(failure);

        assertEquals("系统: 请求暂时无法完成，请稍后重试。", response.getText());
        assertEquals("UNKNOWN", response.getErrorType());
        assertEquals("UNKNOWN", response.getStatus());
    }

    @Test
    public void toAgentResponse_mapsCancelledStatus() {
        RuntimeResult cancelled = RuntimeResult.cancelled("req-1", "session-1", "user-1", "chat", "client-1", "用户取消", 2000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(cancelled);

        assertEquals(false, response.isSuccess());
        assertEquals("系统: 请求已取消", response.getText());
        assertEquals("CANCELLED", response.getErrorType());
        assertEquals("CANCELLED", response.getStatus());
        assertEquals("用户取消", response.getErrorDetail());
    }

    @Test
    public void toAgentResponse_mapsBusyWithoutChangingAidlShape() {
        RuntimeResult busy = RuntimeResult.busy(
                "req-2", "session-1", "user-1", "chat", "client-2", 2_000L);

        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(busy);

        assertEquals(false, response.isSuccess());
        assertEquals("BUSY", response.getErrorType());
        assertEquals("BUSY", response.getStatus());
        assertTrue(response.getText().contains("系统正忙"));
    }

    @Test
    public void toAgentResponse_mapsDuplicateRequest() {
        RuntimeResult duplicate = RuntimeResult.duplicate(
                "req-1", "session-1", "user-1", "chat", "client-1", 2_000L);

        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(duplicate);

        assertEquals("DUPLICATE_REQUEST", response.getErrorType());
        assertEquals("DUPLICATE_REQUEST", response.getStatus());
    }

    @Test
    public void toAgentResponse_keepsKnowledgeCodesButNeverLeaksInternalDetail() {
        RuntimeResult failure = RuntimeResult.failure("req", "session", "user", "chat", "client",
                "KNOWLEDGE_CITATION_INVALID", "D:\\private\\data.mdb body=secret", 2_000L);
        AgentResponse response = new RuntimeResponseMapper().toAgentResponse(failure);

        assertEquals("KNOWLEDGE_CITATION_INVALID", response.getErrorType());
        assertEquals("系统: 车辆知识回答校验未通过，请重新提问。", response.getText());
        assertFalse(response.getText().contains("data.mdb"));
    }
}
