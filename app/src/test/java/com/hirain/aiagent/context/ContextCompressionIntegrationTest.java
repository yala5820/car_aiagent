package com.hirain.aiagent.context;

import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.LongTermMemorySnapshot;
import com.hirain.aiagent.memory.MemoryCompactionPlan;
import com.hirain.aiagent.memory.MemoryCompactionResult;
import com.hirain.aiagent.memory.MemoryEntry;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.trace.AgentTraceRecorder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextCompressionIntegrationTest {

    static class FakeMemoryGateway implements ContextMemoryGateway {
        final AtomicInteger planCount = new AtomicInteger(0);
        final AtomicInteger executeCount = new AtomicInteger(0);
        final ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();

        @Override public ChatMemory chatMemoryForSession(String sessionId, int maxMessages) { return chatMemory; }
        @Override public MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
            return new MemorySnapshot(sessionId, chatMemory.messages(), 0, "");
        }
        @Override public LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
            return new LongTermMemorySnapshot(userId, new ArrayList<MemoryEntry>(), 0L);
        }
        @Override public void extractTurnMemory(String userId, String sessionId, String userMessage, String aiResponse, AgentTraceRecorder trace) {}
        @Override public MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
            planCount.incrementAndGet();
            return new MemoryCompactionPlan(sessionId, targetTokens, 100,
                    List.of(SystemMessage.from("compressed")), 20);
        }
        @Override public MemoryCompactionResult executeCompactionPlan(MemoryCompactionPlan plan, AgentTraceRecorder trace) {
            executeCount.incrementAndGet();
            return new MemoryCompactionResult(true, true, 100, 20, true, null);
        }
    }

    @Test
    public void compressionDecision_planAndExecuteCalled() {
        FakeMemoryGateway gateway = new FakeMemoryGateway();
        ContextBuildInput input = ContextBuildInput.builder()
                .memoryGateway(gateway)
                .budgetManager(ContextBudgetManager.defaultBudget())
                .build();

        // 构建一个包含 TEXT 场景的 ContextAssemblyRequest
        RequestSession session = com.hirain.aiagent.context.TestRequestSessions.textSession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "你好");
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(input);
        ContextPrepareResult prepareResult = orch.prepare(session, ContextCancelChecker.neverCancelled());

        ContextAssemblyRequest req = new ContextAssemblyRequest(
                prepareResult.frame(), 0,
                ModelContextWindowProfiles.qwenTurboDemo(),
                ContextCancelChecker.neverCancelled(), false, session);

        ContextAssemblyResult result = orch.assemble(req);

        assertNotNull("Assembly should produce a result", result);
        // 压缩在生产 assemble 路径中已停用；plan/execute 均不应被调用
        assertEquals("Plan should NOT be called (compression disabled)", 0, gateway.planCount.get());
        assertEquals("Execute should NOT be called (compression disabled)", 0, gateway.executeCount.get());
    }
}
