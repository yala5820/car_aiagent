package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
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

        RequestSession session = factory.create(request, "chat", traceContext, null);

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

        RequestSession session = factory.create(request, "chat", null, null);

        assertEquals("req-existing", session.requestId());
        assertEquals("session-123", session.sessionId());
        assertEquals("session-123", session.userId());
        assertEquals("launcher", session.sourceApp());
        assertEquals("打开车窗", session.userInput());
        assertEquals("session-123", session.orchestratorContext().get("user_id"));
    }

    @Test
    public void create_storesIntentResult() {
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");
        IntentResult intentResult = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");
        RequestSessionFactory factory = new RequestSessionFactory(() -> "req-fixed", () -> 1234L);

        RequestSession session = factory.create(request, "chat", null, intentResult);

        assertEquals(IntentTag.VEHICLE_AC, session.intentResult().intentTag());
    }
}
