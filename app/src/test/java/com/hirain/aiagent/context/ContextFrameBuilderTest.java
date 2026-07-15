package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ContextFrameBuilderTest {

    @Test
    public void buildFromSession_preservesRequestSessionIds() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "friendly", "client-9", "打开空调");

        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .effectivePersonaId("friendly")
                .build();

        assertEquals("req-1", frame.requestId());
        assertEquals("conv-1", frame.sessionId());
        assertEquals("user-a", frame.userId());
        assertEquals("friendly", frame.personaId());
        assertEquals("client-9", frame.clientMessageId());
        assertEquals("打开空调", frame.rawUserInput());
    }
}
