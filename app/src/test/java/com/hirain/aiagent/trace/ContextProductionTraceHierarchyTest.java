package com.hirain.aiagent.trace;

import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry;
import com.hirain.aiagent.context.ContextAssemblyRequest;
import com.hirain.aiagent.context.ContextAssemblyResult;
import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextBudgetPolicy;
import com.hirain.aiagent.context.ContextCancelChecker;
import com.hirain.aiagent.context.ContextOrchestrator;
import com.hirain.aiagent.context.ContextPrepareResult;
import com.hirain.aiagent.prompt.PromptManager;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.opentelemetry.sdk.trace.data.SpanData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 ContextOrchestrator 通过 ContextTraceRecorder 创建 prepare/assemble span，
 * 且父 span 均为 agent.loop。
 */
public class ContextProductionTraceHierarchyTest {

    private static class JvmToolRegistry extends ToolRegistry {
        final Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        JvmToolRegistry() { for (String n : ToolGroupRegistry.defaultRegistry().allToolNames())
            specs.put(n, ToolSpecification.builder().name(n).build());
        }
        @Override public List<ToolSpecification> toolSpecificationsByNames(List<String> names) {
            if (names == null || names.isEmpty()) return List.of();
            List<ToolSpecification> r = new ArrayList<>();
            for (String n : names) { ToolSpecification s = specs.get(n); if (s != null) r.add(s); }
            return r;
        }
        @Override public List<ToolSpecification> enabledToolSpecifications() { return new ArrayList<>(specs.values()); }
        @Override public List<ToolSpecification> getToolSpecifications() { return new ArrayList<>(specs.values()); }
        @Override public int size() { return specs.size(); }
    }

    @Test
    public void prepareAndAssembleSpans_haveCorrectHierarchy() {
        TestTraceSupport.TestSession testSession = TestTraceSupport.redactedSession();

        com.hirain.aiagent.AgentRequest req = new com.hirain.aiagent.AgentRequest();
        req.setInputType("TEXT"); req.setText("打开空调");
        req.setUserId("user-a"); req.setPersonaId("chat");

        TraceContext traceCtx = testSession.traceSession.toTraceContext();
        RequestSession session = new RequestSessionFactory(() -> "req-1", () -> 1000L)
                .create(req, traceCtx,
                        com.hirain.aiagent.intentrouter.IntentResult.of(
                                com.hirain.aiagent.intentrouter.IntentTag.VEHICLE_AC,
                                com.hirain.aiagent.intentrouter.IntentConfidence.HIGH,
                                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC"),
                        com.hirain.aiagent.toolgroup.ToolGroupSelectionResult.enriched(
                                ToolGroupRegistry.defaultRegistry(),
                                List.of(com.hirain.aiagent.toolgroup.ToolGroupId.AC_GROUP,
                                        com.hirain.aiagent.toolgroup.ToolGroupId.BASIC_STATUS_GROUP),
                                "intent:VEHICLE_AC",
                                com.hirain.aiagent.intentrouter.IntentConfidence.HIGH, false),
                        "conv-1");

        PromptManager pm = new PromptManager(null) {
            @Override public String render(String t) { return "System prompt for " + t; }
        };
        com.hirain.aiagent.memory.ContextMemoryGateway mg = new com.hirain.aiagent.memory.ContextMemoryGateway() {
            final dev.langchain4j.memory.ChatMemory chatMem =
                    dev.langchain4j.memory.chat.MessageWindowChatMemory.builder().maxMessages(50).build();
            @Override public dev.langchain4j.memory.ChatMemory chatMemoryForSession(String sessionId, int maxMessages) { return chatMem; }
            @Override public com.hirain.aiagent.memory.MemorySnapshot sessionMemorySnapshot(String sessionId, int maxMessages) {
                return new com.hirain.aiagent.memory.MemorySnapshot(sessionId, chatMem.messages(), 0, "");
            }
            @Override public com.hirain.aiagent.memory.LongTermMemorySnapshot longTermMemorySnapshot(String userId) {
                return new com.hirain.aiagent.memory.LongTermMemorySnapshot(userId, new java.util.ArrayList<>(), 0L);
            }
            @Override public void extractTurnMemory(String userId, String sessionId, String userMessage, String aiResponse, AgentTraceRecorder trace) {}
            @Override public com.hirain.aiagent.memory.MemoryCompactionPlan planSessionCompaction(String sessionId, int targetTokens) {
                return new com.hirain.aiagent.memory.MemoryCompactionPlan(sessionId, targetTokens, 0, java.util.List.of(), 0);
            }
            @Override public com.hirain.aiagent.memory.MemoryCompactionResult executeCompactionPlan(
                    com.hirain.aiagent.memory.MemoryCompactionPlan plan, AgentTraceRecorder trace) {
                return com.hirain.aiagent.memory.MemoryCompactionResult.notExecuted();
            }
        };
        ContextOrchestrator orch = ContextOrchestrator.defaultForText(
                ContextBuildInput.builder()
                        .promptManager(pm)
                        .memoryGateway(mg)
                        .toolRegistry(new JvmToolRegistry())
                        .toolGroupRegistry(ToolGroupRegistry.defaultRegistry())
                        .vehicleStatusProvider(() -> "{\"speed\":0}")
                        .build());

        // 创建 agent.loop span 作为父 span
        io.opentelemetry.api.trace.Span loopSpan = testSession.traceSession.tracer()
                .spanBuilder("agent.loop").startSpan();
        try (io.opentelemetry.context.Scope scope = loopSpan.makeCurrent()) {
            ContextPrepareResult pr = orch.prepare(session, ContextCancelChecker.neverCancelled());
            assertTrue("prepare should succeed", pr.isSuccess());

            ContextBudgetPolicy policy = com.hirain.aiagent.context.ModelContextWindowProfiles.qwenTurboDemo();
            ContextAssemblyRequest req2 = new ContextAssemblyRequest(
                    pr.frame(), 0, policy, ContextCancelChecker.neverCancelled(), false, session);
            ContextAssemblyResult ar = orch.assemble(req2);
            assertTrue("assemble should succeed", ar.success());
        } finally {
            loopSpan.end();
        }
        testSession.close();

        // 验证 span 层级
        SpanData prepareSpan = findSpan(testSession.exporter.spans, "context.prepare");
        assertNotNull("context.prepare span should exist", prepareSpan);
        assertEquals("context.prepare parent should be agent.loop",
                loopSpan.getSpanContext().getSpanId(), prepareSpan.getParentSpanId());

        SpanData assembleSpan = findSpan(testSession.exporter.spans, "context.assemble");
        assertNotNull("context.assemble span should exist", assembleSpan);
        assertEquals("context.assemble parent should be agent.loop",
                loopSpan.getSpanContext().getSpanId(), assembleSpan.getParentSpanId());

        // 验证预算和 Provider 统计属性
        assertTrue("prepare span should have provider.count",
                prepareSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("provider.count")) > 0);
        assertTrue("assemble span should have message.count",
                assembleSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("message.count")) > 0);
        assertTrue("assemble span should have tokens.estimated",
                assembleSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("tokens.estimated")) > 0);
        assertNotNull("assemble span should have budget.within",
                assembleSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey("budget.within")));
    }

    private static SpanData findSpan(java.util.List<SpanData> spans, String name) {
        for (SpanData span : spans) { if (name.equals(span.getName())) return span; }
        return null;
    }
}
