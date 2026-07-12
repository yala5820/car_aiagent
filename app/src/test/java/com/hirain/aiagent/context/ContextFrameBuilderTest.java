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
    }
}
