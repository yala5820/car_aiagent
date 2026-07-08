package com.hirain.aiagent.core;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextFrame;
import com.hirain.aiagent.context.ContextMode;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.context.TestRequestSessions;
import com.hirain.aiagent.core.preprocessor.ContextExtraPreProcessor;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentLoopOrchestratorContextInjectionTest {
    @Test
    public void hybridContextFlowsFromOrchestratorThroughPreProcessorIntoMessages() {
        // 完整链路：ContextOrchestrator → ContextFrame → toOrchestratorContext → AgentLoopContext → ContextExtraPreProcessor
        RequestSession session = TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "打开空调");
        ContextOrchestrator orchestrator = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .mode(ContextMode.HYBRID_EXTRA_CONTEXT)
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .vehicleStatusProvider(() -> "{\"speed\":0}")
                        .timeProvider(() -> 1000L)
                        .build());
        ContextFrame frame = orchestrator.build(session).frame();
        Map<String, Object> context = frame.toOrchestratorContext(session.orchestratorContext());

        AgentLoopContext ctx = new AgentLoopContext(
                session.userInput(), session.personaId(), context);
        List<ChatMessage> messages = new ContextExtraPreProcessor().prepare(ctx);

        String combined = messages.toString();
        assertTrue(combined.contains("【运行时上下文】"));
        assertTrue(combined.contains("【意图上下文】"));
        assertTrue(combined.contains("【工具组上下文】"));
        assertFalse(combined.contains("当前时间："));
        assertFalse(combined.contains("【长期记忆】"));
    }
}
