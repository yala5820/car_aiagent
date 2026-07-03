package com.hirain.aiagent.core;

import static org.junit.Assert.assertNull;

import com.hirain.aiagent.trace.TraceContext;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AgentLoopOrchestratorTraceTest {

    @Test
    public void extractTraceSessionReturnsNullWhenContextIsMissing() {
        Map<String, Object> extraContext = new HashMap<>();
        extraContext.put("scene", "rainy_day");

        assertNull(AgentLoopOrchestrator.extractTraceSession(extraContext));
    }

    @Test
    public void extractTraceSessionReturnsNullWhenContextHasWrongType() {
        Map<String, Object> extraContext = new HashMap<>();
        extraContext.put(TraceContext.TRACE_CONTEXT_KEY, "not_trace_context");

        assertNull(AgentLoopOrchestrator.extractTraceSession(extraContext));
    }

    @Test
    public void extractTraceSessionReturnsNullWhenTraceIsInactive() {
        Map<String, Object> extraContext = new HashMap<>();
        extraContext.put(TraceContext.TRACE_CONTEXT_KEY, new TraceContext(
                "00000000000000000000000000000000",
                "0000000000000000",
                null));

        assertNull(AgentLoopOrchestrator.extractTraceSession(extraContext));
    }
}
