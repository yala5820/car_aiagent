package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionStatus;
import com.hirain.aiagent.trace.TraceContext;

import org.junit.Test;

import java.util.List;

public class RequestSessionFactoryTest {

    @Test
    public void create_generatesRequestIdAndUsesDefaultUserWithoutCreatingSessionId() {
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");
        TraceContext traceContext = new TraceContext("trace-1", "span-1", null);
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

        RequestSession session = factory.create(request, traceContext, null,
                ToolGroupSelectionResult.fallback("test_default"));

        assertEquals("req-fixed", session.requestId());
        assertNull(session.sessionId());
        assertEquals("default_user", session.userId());
        assertEquals("TEXT", session.inputType());
        assertEquals("chat", session.personaId());
        assertEquals("打开空调", session.userInput());
        assertEquals(1234L, session.startedAtMs());
        assertSame(traceContext, session.traceContext());
        assertEquals("default_user", session.orchestratorContext().get("user_id"));
        assertSame(traceContext, session.orchestratorContext().get(TraceContext.TRACE_CONTEXT_KEY));
    }

    @Test
    public void create_preservesExistingRequestIdAndSessionId() {
        AgentRequest request = new AgentRequest();
        request.setRequestId("req-existing");
        request.setSessionId("session-123");
        request.setInputType("TEXT");
        request.setSourceApp("launcher");
        request.setText("打开车窗");
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

        RequestSession session = factory.create(request, null, null,
                ToolGroupSelectionResult.fallback("test_default"));

        assertEquals("req-existing", session.requestId());
        assertEquals("session-123", session.sessionId());
        assertEquals("default_user", session.userId());
        assertEquals("launcher", session.sourceApp());
        assertEquals("打开车窗", session.userInput());
        assertEquals("default_user", session.orchestratorContext().get("user_id"));
    }

    @Test
    public void create_storesIntentResult() {
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");
        IntentResult intentResult = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

        RequestSession session = factory.create(request, null, intentResult,
                ToolGroupSelectionResult.fallback("test_default"));

        assertEquals(IntentTag.VEHICLE_AC, session.intentResult().intentTag());
    }

    @Test
    public void create_storesToolGroupSelectionResultWithoutAddingToOrchestratorContext() {
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");
        IntentResult intentResult = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");
        ToolGroupSelectionResult toolGroups = ToolGroupSelectionResult.of(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                List.of("set_ac_status"),
                "intent:VEHICLE_AC",
                IntentConfidence.HIGH,
                false);
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

        RequestSession session = factory.create(request, null, intentResult, toolGroups);

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                session.toolGroupSelectionResult().selectedGroupIds());
        assertEquals("intent:VEHICLE_AC", session.toolGroupSelectionResult().selectionReason());
        assertFalse(session.orchestratorContext().containsKey("selected_tool_groups"));
        assertFalse(session.orchestratorContext().containsKey("selected_tool_names"));
    }

    @Test
    public void create_usesRequestUserIdInsteadOfSessionId() {
        AgentRequest request = new AgentRequest();
        request.setRequestId("req-1");
        request.setSessionId("session-123");
        request.setUserId("user-A");
        request.setPersonaId("warm");
        request.setClientMessageId("client-9");
        request.setInputType("TEXT");
        request.setText("你好");
        RequestSessionFactory factory = new RequestSessionFactory(() -> "generated", () -> 1000L);

        RequestSession session = factory.create(request, null, null,
                ToolGroupSelectionResult.fallback("test_default"));

        assertEquals("user-A", session.userId());
        assertEquals("session-123", session.sessionId());
        assertEquals("warm", session.personaId());
        assertEquals("client-9", session.clientMessageId());
        assertEquals("user-A", session.orchestratorContext().get("user_id"));
        assertEquals("warm", session.orchestratorContext().get("persona_id"));
        assertEquals("client-9", session.orchestratorContext().get("client_message_id"));
    }

    @Test
    public void create_withNullSelection_failsClosed() {
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-1", () -> 1000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("你好");

        RequestSession session = factory.create(request, null,
                IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                        List.of(), "你好", "TEXT", "test"),
                null); // 传入 null selection

        assertNotNull(session.toolGroupSelectionResult());
        assertEquals("missing_tool_group_selection",
                session.toolGroupSelectionResult().selectionReason());
        assertEquals(ToolGroupSelectionStatus.FAILED_CLOSED,
                session.toolGroupSelectionResult().status());
        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().isEmpty());
        assertTrue(session.toolGroupSelectionResult().selectedToolNames().isEmpty());
    }

    @Test
    public void create_usesAdmissionDeadlineWithoutRestartingClock() {
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-1", () -> 9_999L);
        AgentRequest request = new AgentRequest();
        request.setRequestId("req-1");
        request.setInputType("TEXT");
        request.setText("你好");
        RequestDeadline admissionDeadline = new RequestDeadline(1_000L, 30_000L);

        RequestSession session = factory.create(request, null, null,
                ToolGroupSelectionResult.fallback("test_default"),
                "session-1", admissionDeadline);

        assertSame(admissionDeadline, session.deadline());
        assertEquals(1_000L, session.startedAtMs());
        assertEquals(31_000L, session.deadline().deadlineAtMs());
    }
}
