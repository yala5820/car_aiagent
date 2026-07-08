package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class ContextFrameBuilderTest {

    @Test
    public void contextSection_isImmutableAndKeepsMetadata() {
        ContextSection section = new ContextSection(
                ContextSectionType.RUNTIME,
                "RuntimeContextProvider",
                true,
                "requestId=req-1",
                15,
                false,
                Map.of("request_id", "req-1"));

        assertEquals(ContextSectionType.RUNTIME, section.type());
        assertEquals("RuntimeContextProvider", section.providerName());
        assertFalse(section.truncated());
        assertThrows(UnsupportedOperationException.class,
                () -> section.metadata().put("x", "y"));
    }

    @Test
    public void buildFromSession_preservesRequestSessionIds() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");

        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .effectivePersonaId("friendly")
                .renderedExtraContext("【Context】\nintent=VEHICLE_AC")
                .tokenEstimate(12)
                .build();

        assertEquals("req-1", frame.requestId());
        assertEquals("conv-1", frame.sessionId());
        assertEquals("user-a", frame.userId());
        assertEquals("friendly", frame.personaId());
        assertEquals("client-9", frame.clientMessageId());
        assertEquals("打开空调", frame.rawUserInput());
        assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT, frame.mode());
    }

    @Test
    public void toOrchestratorContext_preservesCallerExtraContext() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .renderedExtraContext("【运行时上下文】")
                .build();
        java.util.Map<String, Object> base = new java.util.HashMap<>();
        base.put("extra_context", "caller value");

        java.util.Map<String, Object> merged = frame.toOrchestratorContext(base);

        assertEquals("caller value", merged.get("extra_context"));
        assertEquals("caller value", merged.get("caller_extra_context"));
        assertEquals("【运行时上下文】", merged.get("context_rendered_extra"));
    }

    @Test
    public void toOrchestratorContext_nullBaseContextDoesNotCrash() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .renderedExtraContext("【运行时上下文】")
                .build();

        java.util.Map<String, Object> merged = frame.toOrchestratorContext(null);

        assertEquals(ContextMode.HYBRID_EXTRA_CONTEXT.name(), merged.get("context_mode"));
        assertEquals("【运行时上下文】", merged.get("context_rendered_extra"));
    }
}
