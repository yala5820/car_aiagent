package com.hirain.aiagent.context;

import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.LongTermMemorySnapshot;
import com.hirain.aiagent.memory.MemoryCompactionPlan;
import com.hirain.aiagent.memory.MemoryCompactionResult;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.trace.AgentTraceRecorder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextCompressionIntegrationTest {

    @Test
    public void longHistory_compactsOnce_reloadsAndPassesSecondAssembly() {
        FakeMemoryGateway memory = new FakeMemoryGateway();
        ContextOrchestrator orchestrator = orchestrator(memory);
        RequestSession session = TestRequestSessions.chatOnlySession(
                "req-1", "conv-1", "user-a", "chat", "client-1", "当前问题");
        ContextPrepareResult prepared = orchestrator.prepare(
                session, ContextCancelChecker.neverCancelled());

        ContextAssemblyResult result = orchestrator.assemble(new ContextAssemblyRequest(
                prepared.frame(), 0, new ContextBudgetPolicy(900, 0, 0),
                ContextCancelChecker.neverCancelled(), false, session));

        assertTrue(result.success());
        assertTrue(result.budgetReport().withinBudget());
        assertTrue(result.compressionAttempted());
        assertTrue(result.memoryCompacted());
        assertEquals(1, memory.planCount.get());
        assertEquals(1, memory.executeCount.get());
        String modelInput = result.messages().toString();
        assertTrue(modelInput.contains("压缩后的历史摘要"));
        assertFalse(modelInput.contains("旧问题-1"));
        assertTrue(modelInput.contains("最近问题-3"));
    }

    @Test
    public void compressionAlreadyAttempted_doesNotCallMemoryAgain() {
        FakeMemoryGateway memory = new FakeMemoryGateway();
        ContextOrchestrator orchestrator = orchestrator(memory);
        RequestSession session = TestRequestSessions.chatOnlySession(
                "req-2", "conv-2", "user-a", "chat", "client-2", "当前问题");
        ContextPrepareResult prepared = orchestrator.prepare(
                session, ContextCancelChecker.neverCancelled());

        ContextAssemblyResult result = orchestrator.assemble(new ContextAssemblyRequest(
                prepared.frame(), 0, new ContextBudgetPolicy(900, 0, 0),
                ContextCancelChecker.neverCancelled(), true, session));

        assertFalse(result.success());
        assertEquals(ContextErrorCode.CONTEXT_BUDGET_EXCEEDED, result.errorCode());
        assertTrue(result.compressionAttempted());
        assertEquals(0, memory.planCount.get());
        assertEquals(0, memory.executeCount.get());
    }

    @Test
    public void noCompactableTurns_doesNotMarkCompressionAttempted() {
        FakeMemoryGateway memory = new FakeMemoryGateway();
        memory.hasCompactableHistory = false;
        ContextOrchestrator orchestrator = orchestrator(memory);
        RequestSession session = TestRequestSessions.chatOnlySession(
                "req-3", "conv-3", "user-a", "chat", "client-3", "当前问题");
        ContextPrepareResult prepared = orchestrator.prepare(
                session, ContextCancelChecker.neverCancelled());

        ContextAssemblyResult result = orchestrator.assemble(new ContextAssemblyRequest(
                prepared.frame(), 0, new ContextBudgetPolicy(900, 0, 0),
                ContextCancelChecker.neverCancelled(), false, session));

        assertFalse(result.success());
        assertEquals(ContextErrorCode.MEMORY_COMPACTION_FAILED, result.errorCode());
        assertFalse("摘要模型尚未启动时不得消耗本请求的压缩机会",
                result.compressionAttempted());
        assertEquals(1, memory.planCount.get());
        assertEquals(0, memory.executeCount.get());
    }

    private static ContextOrchestrator orchestrator(FakeMemoryGateway memory) {
        ContextTokenEstimator deterministicEstimator = new ContextTokenEstimator() {
            @Override public int estimateMessages(List<ChatMessage> messages) {
                return messages != null ? messages.size() * 100 : 0;
            }
            @Override public int estimateToolSpecs(List<ToolSpecification> specs) {
                return specs != null ? specs.size() * 100 : 0;
            }
            @Override public int estimateTotal(List<ChatMessage> messages,
                                               List<ToolSpecification> specs) {
                return estimateMessages(messages) + estimateToolSpecs(specs);
            }
        };
        PromptManager prompt = new PromptManager(null) {
            @Override public String render(String templateName) { return "系统提示词"; }
        };
        return ContextOrchestrator.defaultForText(ContextBuildInput.builder()
                .memoryGateway(memory)
                .promptManager(prompt)
                .tokenEstimator(deterministicEstimator)
                .build());
    }

    private static final class FakeMemoryGateway implements ContextMemoryGateway {
        private final AtomicInteger planCount = new AtomicInteger();
        private final AtomicInteger executeCount = new AtomicInteger();
        private boolean hasCompactableHistory = true;
        private final ChatMemory liveMemory = MessageWindowChatMemory.builder()
                .maxMessages(50).build();
        private List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("旧问题-1"), AiMessage.from("旧回答-1"),
                UserMessage.from("旧问题-2"), AiMessage.from("旧回答-2"),
                UserMessage.from("最近问题-3"), AiMessage.from("最近回答-3"),
                UserMessage.from("最近问题-4"), AiMessage.from("最近回答-4")));
        private String summary = "";

        @Override public ChatMemory chatMemoryForSession(String sessionId, int maxMessages) {
            return liveMemory;
        }
        @Override public MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
            return new MemorySnapshot(sessionId, messages, messages.size(), summary);
        }
        @Override public LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
            return new LongTermMemorySnapshot(userId, List.of(), 0L);
        }
        @Override public void extractTurnMemory(String userId, String sessionId,
                                                String userMessage, String aiResponse,
                                                AgentTraceRecorder trace) {}
        @Override public MemoryCompactionPlan planSessionCompaction(String sessionId,
                                                                   int targetTokens) {
            planCount.incrementAndGet();
            if (!hasCompactableHistory) {
                return new MemoryCompactionPlan(sessionId, targetTokens, 800, "snapshot-1",
                        messages, "", List.of(), messages);
            }
            return new MemoryCompactionPlan(sessionId, targetTokens, 800, "snapshot-1",
                    messages, "", messages.subList(0, 4), messages.subList(4, 8));
        }
        @Override public MemoryCompactionResult executeCompactionPlan(
                MemoryCompactionPlan plan, AgentTraceRecorder trace) {
            executeCount.incrementAndGet();
            summary = "压缩后的历史摘要";
            messages = new ArrayList<>(plan.protectedMessages());
            return new MemoryCompactionResult(true, true, 800, 500, false, null);
        }
    }
}
