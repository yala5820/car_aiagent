package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.CancelRequestResult;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import org.junit.Test;

import java.util.HashMap;

public class ActiveRequestRegistryTest {

    @Test
    public void cancel_returnsAcceptedAndMarksRequestCancelled() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        RequestSession session = newTestSession("req-1");
        registry.register(session);

        CancelRequestResult result = registry.cancel("req-1", "user_stop", 1000L);

        assertTrue(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_ACCEPTED, result.getStatus());
        assertTrue(registry.get("req-1").isCancelled());
    }

    @Test
    public void cancel_returnsAlreadyFinishedForRecentlyFinishedRequest() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        RequestSession session = newTestSession("req-1");
        registry.register(session);
        registry.finish("req-1");

        CancelRequestResult result = registry.cancel("req-1", "user_stop", System.currentTimeMillis());

        assertFalse(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_ALREADY_FINISHED, result.getStatus());
    }

    @Test
    public void cancel_returnsNotFoundForUnknownRequest() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();

        CancelRequestResult result = registry.cancel("non-existent", "reason", 1000L);

        assertFalse(result.isSuccess());
        assertEquals(CancelRequestResult.STATUS_NOT_FOUND, result.getStatus());
        assertNull(registry.get("non-existent"));
    }

    @Test
    public void tryComplete_raceOnlyFirstWins() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        RequestSession session = newTestSession("req-1");
        registry.register(session);

        assertTrue(registry.tryComplete("req-1", ActiveRequest.TerminalState.COMPLETED));
        assertFalse(registry.tryComplete("req-1", ActiveRequest.TerminalState.TIMEOUT));
        assertFalse(registry.tryComplete("req-1", ActiveRequest.TerminalState.FAILED));
    }

    private static RequestSession newTestSession(String requestId) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(requestId);
        request.setSessionId("session-1");
        request.setUserId("user-1");
        request.setPersonaId("chat");
        request.setText("你好");
        request.setInputType("TEXT");
        return new RequestSession(
                request,
                requestId,
                "session-1",
                "user-1",
                "test",
                "TEXT",
                "chat",
                "client-1",
                "你好",
                1000L,
                null,
                IntentResult.unknown("你好", "TEXT", "test_default"),
                ToolGroupSelectionResult.fallback("test_default"),
                new HashMap<>());
    }
}
