package com.hirain.aiagent.runtime;

import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.prompt.PromptManager;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 {@link AgentExecutor} 新 SAM {@code execute(RequestSession, ContextPrepareResult)}。
 */
public class AgentExecutorCompatibilityTest {
    @Test
    public void requestSessionAndPrepareResultPassedToExecutor() {
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        PromptManager pm = new PromptManager(null) {
            @Override public String render(String templateName) {
                return "System prompt for " + templateName;
            }
        };
        com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry tr = new com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry() {
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecificationsByNames(
                    java.util.List<String> names) {
                return names != null ? names.stream().map(n -> dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(n).description("default " + n).build()).toList() : java.util.List.of();
            }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> enabledToolSpecifications() { return java.util.List.of(); }
            @Override public java.util.List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() { return java.util.List.of(); }
            @Override public int size() { return 0; }
        };
        com.hirain.aiagent.context.ContextOrchestrator orchestrator =
                com.hirain.aiagent.context.ContextOrchestrator.defaultForText(
                        com.hirain.aiagent.context.ContextBuildInput.builder()
                                .promptManager(pm)
                                .toolRegistry(tr)
                                .build());

        ContextPrepareResult prepareResult = orchestrator.prepare(session,
                ContextCancelChecker.neverCancelled());

        assertNotNull("prepareResult should not be null", prepareResult);
        assertTrue("prepareResult should be success", prepareResult.isSuccess());
        assertNotNull("ContextFrame should be present", prepareResult.frame());
        assertEquals("req-1", prepareResult.frame().requestId());

        AgentExecutor executor = (sess, pr) -> {
            assertEquals(session, sess);
            assertEquals(prepareResult, pr);
            return AgentResult.success("ok", 1, 1L, List.of());
        };

        AgentResult result = executor.execute(session, prepareResult);
        assertTrue(result.isSuccess());
    }
}
