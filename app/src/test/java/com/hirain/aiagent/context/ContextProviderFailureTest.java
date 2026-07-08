package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextProviderFailureTest {

    @Test
    public void failureResult_keepsProviderAndReason() {
        ContextProviderResult result =
                ContextProviderResult.failure("BrokenProvider", "boom");

        assertFalse(result.success());
        assertEquals("BrokenProvider", result.providerName());
        assertEquals("boom", result.errorReason());
    }

    @Test
    public void build_providerExceptionCreatesDebugInfoAndContinues() {
        ContextProvider broken = new ContextProvider() {
            @Override public String name() { return "BrokenProvider"; }
            @Override public ContextSectionType type() { return ContextSectionType.DEBUG; }
            @Override public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
                throw new IllegalStateException("boom");
            }
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(
                ContextBuildInput.builder().mode(ContextMode.HYBRID_EXTRA_CONTEXT).build(),
                List.of(new RuntimeContextProvider(), broken));

        ContextBuildResult result = orchestrator.build(TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好"));

        assertTrue(result.success());
        assertTrue(result.fallbackUsed());
        assertEquals("boom", result.frame().debugInfo().providerErrors().get("BrokenProvider"));
    }
}
