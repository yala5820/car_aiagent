package com.hirain.aiagent.context;

import com.hirain.aiagent.context.provider.RuntimeContextProvider;
import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextProviderFailureTest {

    @Test
    public void failureResult_keepsProviderAndReason() {
        ContextProviderResult result =
                ContextProviderResult.failure("BrokenProvider", "boom", ContextErrorCode.CONTEXT_INTERNAL_ERROR);

        assertFalse(result.success());
        assertEquals("BrokenProvider", result.providerName());
        assertEquals("boom", result.errorReason());
    }

    @Test
    public void build_providerExceptionCreatesOutcomeAndContinues() {
        ContextProvider broken = new ContextProvider() {
            @Override public String name() { return "BrokenProvider"; }
            @Override public boolean required(RequestSession session, ContextBuildInput input) { return false; }
            @Override public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
                throw new IllegalStateException("boom");
            }
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(
                ContextBuildInput.builder().build(),
                List.of(new RuntimeContextProvider(), broken));

        ContextPrepareResult result = orchestrator.prepare(TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好"),
                ContextCancelChecker.neverCancelled());

        // provider 抛出异常但不 required → prepare 仍然成功
        assertTrue(result.isSuccess());
        assertNotNull(result.providerOutcomes());
        // BrokenProvider 的 outcome 应为 FAILED
        boolean foundFailed = result.providerOutcomes().stream()
                .anyMatch(o -> "BrokenProvider".equals(o.providerName())
                        && o.status() == ContextProviderStatus.FAILED);
        assertTrue("BrokenProvider should have FAILED outcome", foundFailed);
    }
}
