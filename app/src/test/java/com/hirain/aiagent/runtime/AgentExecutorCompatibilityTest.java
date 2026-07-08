package com.hirain.aiagent.runtime;

import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextFrameBuilder;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.core.AgentResult;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentExecutorCompatibilityTest {
    @Test
    public void defaultContextFrameExecuteDelegatesToOldExecute() {
        AtomicReference<Map<String, Object>> contextRef = new AtomicReference<>();
        AgentExecutor executor = (userInput, context) -> {
            contextRef.set(context);
            return AgentResult.success("ok", 1, 1L, List.of());
        };
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextFrame frame = ContextFrameBuilder.fromSession(session)
                .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                .renderedExtraContext("【Context】")
                .build();

        AgentResult result = executor.execute(session, frame);

        assertTrue(result.isSuccess());
        assertEquals("【Context】", contextRef.get().get("context_rendered_extra"));
    }
}
