package com.hirain.aiagent.core;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSpanNames;

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

    /**
     * 基线：prompt.assembly span name 常量存在且非空。
     * <p>
     * 完整集成测试需要在 AgentLoop 实际执行时验证该 span 的创建。
     */
    @Test
    public void promptAssemblySpanNameConstantExists() {
        assertNotNull("PROMPT_ASSEMBLY span name should be defined",
                TraceSpanNames.PROMPT_ASSEMBLY);
        assertNotNull("PROMPT_ASSEMBLY should be a non-null string",
                TraceSpanNames.PROMPT_ASSEMBLY);
    }
}
