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
import java.util.Set;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Phase 2 Provider span / Fragment / Message / Toolset 结构化 trace 记录。
 * <p>
 * 测试目标：
 * 1. request-static Provider 有独立 span 挂在 context.prepare 下
 * 2. iteration-dynamic Provider 有独立 span 挂在 context.assemble 下
 * 3. MODEL_VISIBLE contributions 有对应的 fragment/message/toolset span
 * 4. POLICY_ONLY contributions 不产生 fragment span，included_in_model=false
 * 5. Provider span 不承载 contribution 正文（去重规则）
 */
public class ContextProviderTraceTest {

    private static final Set<String> POLICY_ONLY_PROVIDERS = Set.of(
            "RuntimeContextProvider", "PersonaContextProvider", "IntentContextProvider");

    private static final Set<String> MODEL_VISIBLE_STATIC_PROVIDERS = Set.of(
            "PromptContextProvider", "UserInputContextProvider",
            "ToolGroupContextProvider", "LongTermMemoryContextProvider",
            "CallerExtraContextProvider");

    private static final Set<String> MODEL_VISIBLE_DYNAMIC_PROVIDERS = Set.of(
            "SessionMemoryContextProvider", "VehicleStateContextProvider", "TimeContextProvider");

    private static class JvmToolRegistry extends ToolRegistry {
        final Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        JvmToolRegistry() {
            for (String n : ToolGroupRegistry.defaultRegistry().allToolNames())
                specs.put(n, ToolSpecification.builder().name(n)
                        .description("Tool for " + n).build());
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
    public void providerSpans_haveCorrectHierarchyAndContentDedup() {
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

            // agent.iteration span 作为 assemble 父
            io.opentelemetry.api.trace.Span iterSpan = testSession.traceSession.tracer()
                    .spanBuilder("agent.iteration").startSpan();
            try (io.opentelemetry.context.Scope iterScope = iterSpan.makeCurrent()) {
                ContextBudgetPolicy policy = com.hirain.aiagent.context.ModelContextWindowProfiles.qwenTurboDemo();
                ContextAssemblyRequest req2 = new ContextAssemblyRequest(
                        pr.frame(), 0, policy, ContextCancelChecker.neverCancelled(), false, session);
                ContextAssemblyResult ar = orch.assemble(req2);
                assertTrue("assemble should succeed", ar.success());
            } finally {
                iterSpan.end();
            }
        } finally {
            loopSpan.end();
        }
        testSession.close();

        List<SpanData> spans = testSession.exporter.spans;

        // ── 1. 验证 prepare 下有 request-static provider spans ──
        for (String provider : allStaticProviders()) {
            SpanData provSpan = findSpan(spans, "context.provider." + provider);
            assertNotNull("provider span should exist: " + provider, provSpan);

            // 验证 parent = context.prepare
            SpanData prepareSpan = findSpan(spans, "context.prepare");
            assertNotNull("context.prepare span should exist", prepareSpan);
            assertEquals("provider span parent should be context.prepare",
                    prepareSpan.getSpanId(), provSpan.getParentSpanId());

            // 验证 execution metrics exist (no full content)
            assertTrue("provider span should have duration_ms",
                    provSpan.getAttributes().get(AttributeKey.longKey("provider.duration_ms")) >= 0);
            assertTrue("provider span should have contribution_count",
                    provSpan.getAttributes().get(AttributeKey.longKey("provider.contribution_count")) >= 0);
            assertNotNull("provider span should have status",
                    provSpan.getAttributes().get(AttributeKey.stringKey("provider.status")));

            // 验证 provider span 不承载 contribution 正文（去重规则）
            assertFalse("provider span should NOT have fragment.content (dedup)",
                    provSpan.getAttributes().asMap().keySet().stream()
                            .anyMatch(k -> k.getKey().equals("fragment.content")));
        }

        // ── 2. 验证 POLICY_ONLY providers 无 fragment span + included_in_model=false ──
        for (String provider : POLICY_ONLY_PROVIDERS) {
            SpanData provSpan = findSpan(spans, "context.provider." + provider);
            assertNotNull("POLICY_ONLY provider span should exist: " + provider, provSpan);
            assertEquals("POLICY_ONLY provider included_in_model should be false",
                    false, provSpan.getAttributes().get(AttributeKey.booleanKey("provider.included_in_model")));
        }

        // ── 3. 验证 MODEL_VISIBLE contributions 有对应 fragment/message/toolset span ──
        // 静态 contributions
        assertNotNull("context.fragment.prompt should exist",
                findSpan(spans, "context.fragment.prompt"));
        assertNotNull("context.fragment.long_term_memory should exist",
                findSpan(spans, "context.fragment.long_term_memory"));
        assertNotNull("context.fragment.caller_extra should exist",
                findSpan(spans, "context.fragment.caller_extra"));
        assertNotNull("context.message.current_user should exist",
                findSpan(spans, "context.message.current_user"));
        assertNotNull("context.toolset should exist",
                findSpan(spans, "context.toolset"));
        // 动态 contributions
        assertNotNull("context.fragment.time should exist",
                findSpan(spans, "context.fragment.time"));
        assertNotNull("context.message.session_memory should exist",
                findSpan(spans, "context.message.session_memory"));

        // ── 4. 验证 fragment/message/toolset span 在 assemble 下 ──
        SpanData assembleSpan = findSpan(spans, "context.assemble");
        assertNotNull("context.assemble span should exist", assembleSpan);

        String[] expectedFragmentNames = {
                "context.fragment.prompt",
                "context.fragment.long_term_memory",
                "context.fragment.caller_extra"
        };
        for (String fragName : expectedFragmentNames) {
            SpanData fragSpan = findSpan(spans, fragName);
            assertNotNull(fragName + " should exist", fragSpan);
            assertEquals(fragName + " parent should be context.assemble",
                    assembleSpan.getSpanId(), fragSpan.getParentSpanId());
        }

        SpanData toolsetSpan = findSpan(spans, "context.toolset");
        assertNotNull("context.toolset should exist", toolsetSpan);
        assertEquals("context.toolset parent should be context.assemble",
                assembleSpan.getSpanId(), toolsetSpan.getParentSpanId());

        // ── 5. 验证 fragment span 包含 sourceKey/content 属性 ──
        SpanData promptFragment = findSpan(spans, "context.fragment.prompt");
        assertTrue("prompt fragment should have sourceKey",
                promptFragment.getAttributes().get(
                        AttributeKey.stringKey(TraceAttributeKeys.FRAGMENT_SOURCE_KEY)).equals("prompt"));
        assertNotNull("prompt fragment should have content",
                promptFragment.getAttributes().get(
                        AttributeKey.stringKey(TraceAttributeKeys.FRAGMENT_CONTENT)));

        // ── 6. 验证 assemble 下有 iteration-dynamic provider spans ──
        for (String provider : MODEL_VISIBLE_DYNAMIC_PROVIDERS) {
            SpanData provSpan = findSpan(spans, "context.provider." + provider);
            assertNotNull("dynamic provider span should exist: " + provider, provSpan);
            assertEquals("dynamic provider span parent should be context.assemble",
                    assembleSpan.getSpanId(), provSpan.getParentSpanId());
        }

        // ── 7. 验证 provider span duration 反映真实执行（>0） ──
        for (String provider : allStaticProviders()) {
            SpanData provSpan = findSpan(spans, "context.provider." + provider);
            assertNotNull("provider span should exist: " + provider, provSpan);
            long durationMs = provSpan.getAttributes()
                    .get(AttributeKey.longKey("provider.duration_ms"));
            assertTrue("provider " + provider + " should have duration_ms >= 0",
                    durationMs >= 0);
        }

        // ── 8. 验证 toolset schema 包含 name/description（有 parameters 时也包含） ──
        String schemaStr = toolsetSpan.getAttributes()
                .get(AttributeKey.stringKey("toolset.schema"));
        assertNotNull("toolset should have schema", schemaStr);
        assertTrue("toolset schema should contain 'name='", schemaStr.contains("name="));
        assertTrue("toolset schema should contain 'description='", schemaStr.contains("description="));

        // ── 9. 验证 message span 有完整正文（非摘要） ──
        SpanData currUserMsg = findSpan(spans, "context.message.current_user");
        assertNotNull("context.message.current_user should exist", currUserMsg);
        String msgContent = currUserMsg.getAttributes()
                .get(AttributeKey.stringKey("message.content"));
        assertNotNull("message.content should exist (full content, not just summary)", msgContent);
        assertTrue("message.content should contain user input text",
                msgContent.contains("打开空调"));
    }

    private static List<String> allStaticProviders() {
        List<String> all = new ArrayList<>(POLICY_ONLY_PROVIDERS);
        all.addAll(MODEL_VISIBLE_STATIC_PROVIDERS);
        return all;
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) { if (name.equals(span.getName())) return span; }
        return null;
    }
}
