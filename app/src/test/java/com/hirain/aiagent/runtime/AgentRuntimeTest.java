package com.hirain.aiagent.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentRouter;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionStatus;
import com.hirain.aiagent.toolgroup.ToolGroupSelector;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TestTraceSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AgentRuntimeTest {

    @Test
    public void execute_callsExecutorWithNormalizedContext() {
        AtomicReference<String> input = new AtomicReference<>();
        AtomicReference<Map<String, Object>> context = new AtomicReference<>();
        AgentExecutor executor = (session, prepareResult) -> {
            input.set(session.userInput());
            // Phase 6: AgentExecutor 不再经过 toOrchestratorContext，直接使用 prepareResult
            java.util.Map<String, Object> ctx = new java.util.HashMap<>();
            if (prepareResult.frame() != null) {
                ctx.put("user_id", session.userId());
            }
            context.set(ctx);
            return AgentResult.success("完成", 1, 10L, List.of());
        };
        AgentRuntime runtime = new AgentRuntime(executor, () -> "req-fixed", () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");
        TraceContext traceContext = new TraceContext("trace-1", "span-1", null);

        RequestSession session = runtime.startSession(request, traceContext);
        RuntimeResult result = runtime.execute(session);

        assertTrue(result.success());
        assertEquals("完成", result.output());
        assertEquals("打开空调", input.get());
        assertEquals("default_user", context.get().get("user_id"));
        assertEquals("req-fixed", result.requestId());
        assertEquals(3000L, result.timestampMs());
    }

    @Test
    public void execute_mapsExecutorExceptionToExceptionResult() {
        AgentExecutor failingExecutor = (session, prepareResult) -> {
            throw new RuntimeException("模拟失败");
        };
        AgentRuntime runtime = new AgentRuntime(failingExecutor, () -> "req-fixed", () -> 3000L);

        RuntimeResult result = runtime.execute(runtime.startSession(createRequest("测试"), null));

        assertEquals("EXCEPTION", result.errorType());
        assertNotNull(result.errorDetail());
    }

    @Test
    public void timeoutResult_createsTimeoutResultWithSessionIds() {
        AgentRuntime runtime = new AgentRuntime((session, prepareResult) ->
                AgentResult.success("ok", 1, 10L, List.of()), () -> "req-fixed", () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult timeout = runtime.timeoutResult(session);

        assertEquals("req-fixed", timeout.requestId());
        assertNull(timeout.sessionId());
        assertEquals("TIMEOUT", timeout.errorType());
    }

    @Test
    public void startSession_routesIntentAndExecuteStillUsesOriginalExecutor() {
        IntentRouter router = (text, sourceInputType) ->
                IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                        List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC");
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("完成", 1, 10L, List.of()),
                router,
                () -> "req-fixed",
                () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertEquals(IntentTag.VEHICLE_AC, session.intentResult().intentTag());
        assertTrue(result.success());
        assertEquals("完成", result.output());
    }

    @Test
    public void startSession_driverProfileHowToQuestionUsesKnowledgeInsteadOfClarification() {
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("完成", 1, 10L, List.of()),
                () -> "req-driver-profile",
                () -> 3000L);

        RequestSession session = runtime.startSession(createRequest("如何切换驾驶员设定"), null);

        assertEquals(ToolGroupSelectionStatus.SELECTED,
                session.toolGroupSelectionResult().status());
        assertEquals(List.of(ToolGroupId.VEHICLE_KNOWLEDGE_GROUP),
                session.toolGroupSelectionResult().selectedGroupIds());
        assertTrue(session.toolGroupSelectionResult().selectedToolNames()
                .contains("searchVehicleKnowledge"));
    }

    @Test
    public void startSession_routerExceptionFallsBackToUnknownAndExecuteContinues() {
        IntentRouter router = (text, sourceInputType) -> {
            throw new IllegalStateException("router failed");
        };
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("继续执行", 1, 10L, List.of()),
                router,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(createRequest("测试"), null);
        RuntimeResult result = runtime.execute(session);

        assertEquals(IntentTag.UNKNOWN, session.intentResult().intentTag());
        assertTrue(result.success());
        assertEquals("继续执行", result.output());
    }

    @Test
    public void startSession_writesIntentAttributesToTrace() {
        TestTraceSupport.TestSession testSession = TestTraceSupport.redactedSession();

        IntentRouter router = (text, sourceInputType) ->
                IntentResult.of(IntentTag.WEATHER, IntentConfidence.MEDIUM,
                        List.of("天气"), text, sourceInputType, "matched:WEATHER");
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("ok", 1, 10L, List.of()),
                router,
                () -> "req-1",
                () -> 2000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("北京天气");

        runtime.startSession(request, testSession.traceSession.toTraceContext());
        testSession.close();

        SpanData rootSpan = findSpan(testSession.exporter.spans, "agent.request");
        assertEquals("WEATHER", rootSpan.getAttributes()
                .get(AttributeKey.stringKey("agent.intent.tag")));
        assertEquals("MEDIUM", rootSpan.getAttributes()
                .get(AttributeKey.stringKey("agent.intent.confidence")));
        assertEquals("天气", rootSpan.getAttributes()
                .get(AttributeKey.stringKey("agent.intent.matched_keywords")));
        assertEquals("TEXT", rootSpan.getAttributes()
                .get(AttributeKey.stringKey("agent.intent.source_input_type")));
        assertEquals("matched:WEATHER", rootSpan.getAttributes()
                .get(AttributeKey.stringKey("agent.intent.debug_reason")));
    }

    @Test
    public void startSession_selectsToolGroupsAndExecuteStillUsesOriginalExecutor() {
        IntentRouter router = (text, sourceInputType) ->
                IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                        List.of("空调"), text, sourceInputType, "matched:VEHICLE_AC");
        ToolGroupSelector selector = (intentResult, userInput) -> ToolGroupSelectionResult.of(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                List.of("set_ac_status"),
                "intent:VEHICLE_AC",
                IntentConfidence.HIGH,
                false);
        AtomicReference<Map<String, Object>> context = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> {
                    java.util.Map<String, Object> ctx = new java.util.HashMap<>();
                    context.set(ctx);
                    return AgentResult.success("完成", 1, 10L, List.of());
                },
                router,
                selector,
                () -> "req-fixed",
                () -> 3000L);
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("打开空调");

        RequestSession session = runtime.startSession(request, null);
        RuntimeResult result = runtime.execute(session);

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP,
                        ToolGroupId.VEHICLE_KNOWLEDGE_GROUP),
                session.toolGroupSelectionResult().selectedGroupIds());
        assertTrue(result.success());
        // Phase 6: Context 独占链路，prepareResult 直接驱动 AgentLoop
    }

    @Test
    public void startSession_toolGroupSelectorExceptionFailsClosedBeforeExecutor() {
        ToolGroupSelector failingSelector = (intentResult, userInput) -> {
            throw new IllegalStateException("selector failed");
        };
        AtomicInteger executorCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> {
                    executorCalls.incrementAndGet();
                    return AgentResult.success("不应执行", 1, 10L, List.of());
                },
                (text, sourceInputType) -> IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                        List.of(), text, sourceInputType, "fallback_chat"),
                failingSelector,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(createRequest("测试"), null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().isEmpty());
        assertTrue(session.toolGroupSelectionResult().selectedToolNames().isEmpty());
        assertEquals("tool_group_selector_exception",
                session.toolGroupSelectionResult().selectionReason());
        assertEquals(ToolGroupSelectionStatus.FAILED_CLOSED,
                session.toolGroupSelectionResult().status());
        assertFalse(session.toolGroupSelectionResult().allToolsFallback());
        assertTrue(session.toolGroupSelectionResult().fallbackUsed());
        assertEquals("TOOL_SELECTION_FAILED", result.errorType());
        assertEquals(0, executorCalls.get());
    }

    @Test
    public void startSession_toolGroupSelectorReturnsNull_failsClosed() {
        ToolGroupSelector nullReturningSelector = (intentResult, userInput) -> null;
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("继续执行", 1, 10L, List.of()),
                (text, sourceInputType) -> IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                        List.of(), text, sourceInputType, "fallback_chat"),
                nullReturningSelector,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(createRequest("测试"), null);
        RuntimeResult result = runtime.execute(session);

        assertTrue(session.toolGroupSelectionResult().selectedGroupIds().isEmpty());
        assertEquals("tool_group_selector_null",
                session.toolGroupSelectionResult().selectionReason());
        assertEquals(ToolGroupSelectionStatus.FAILED_CLOSED,
                session.toolGroupSelectionResult().status());
        assertFalse(session.toolGroupSelectionResult().allToolsFallback());
        assertEquals("TOOL_SELECTION_FAILED", result.errorType());
    }

    @Test
    public void startSession_preservesPersonaIdFromRequest() {
        AgentRequest request = new AgentRequest();
        request.setInputType("TEXT");
        request.setText("你好");
        request.setPersonaId("warm");
        AgentRuntime runtime = new AgentRuntime(
                (input, context) -> AgentResult.success("ok", 1, 10L, List.of()),
                () -> "req-1",
                () -> 1000L);

        RequestSession session = runtime.startSession(request, null);

        assertEquals("warm", session.personaId());
        assertEquals("warm", session.orchestratorContext().get("persona_id"));
    }

    @Test
    public void startSession_toolGroupSelectorException_neverContainsAllTools() {
        ToolGroupSelector failingSelector = (intentResult, userInput) -> {
            throw new IllegalStateException("selector failed");
        };
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> AgentResult.success("继续执行", 1, 10L, List.of()),
                (text, sourceInputType) -> IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                        List.of(), text, sourceInputType, "fallback_chat"),
                failingSelector,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(createRequest("测试"), null);
        assertFalse(session.toolGroupSelectionResult().selectionReason()
                .contains("all_tools"));
        assertFalse(session.toolGroupSelectionResult().allToolsFallback());
        assertTrue(session.toolGroupSelectionResult().selectedToolNames().isEmpty());
    }

    @Test
    public void execute_clarificationRequiredReturnsFixedTextBeforeExecutor() {
        AtomicInteger executorCalls = new AtomicInteger();
        ToolGroupSelector selector = (intentResult, userInput) ->
                ToolGroupSelectionResult.clarificationRequired(
                        "clarification:test", IntentConfidence.LOW);
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> {
                    executorCalls.incrementAndGet();
                    return AgentResult.success("不应执行", 1, 1L, List.of());
                },
                (text, sourceInputType) -> IntentResult.of(
                        IntentTag.CHAT, IntentConfidence.LOW, List.of(), text,
                        sourceInputType, "fallback_chat"),
                selector,
                () -> "req-fixed",
                () -> 3000L);

        RuntimeResult result = runtime.execute(runtime.startSession(createRequest("车里不舒服"), null));

        assertTrue(result.success());
        assertEquals("请明确要控制空调、车窗、座椅、车门还是底盘。", result.output());
        assertEquals(0, executorCalls.get());
    }

    @Test
    public void execute_compoundKnowledgeClarificationReturnsAccurateText() {
        AtomicInteger executorCalls = new AtomicInteger();
        ToolGroupSelector selector = (intentResult, userInput) ->
                ToolGroupSelectionResult.clarificationRequired(
                        com.hirain.aiagent.rag.policy.KnowledgeCapabilityPlanner.COMPOUND_REQUEST_REQUIRES_SPLIT,
                        IntentConfidence.LOW);
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> {
                    executorCalls.incrementAndGet();
                    return AgentResult.success("不应执行", 1, 1L, List.of());
                },
                (text, sourceInputType) -> IntentResult.of(
                        IntentTag.CHAT, IntentConfidence.LOW, List.of(), text,
                        sourceInputType, "fallback_chat"),
                selector,
                () -> "req-fixed",
                () -> 3000L);

        RuntimeResult result = runtime.execute(runtime.startSession(createRequest("查询后再操作"), null));

        assertTrue(result.success());
        assertEquals("当前请求同时包含知识查询与其他操作，请拆分为两条消息分别发送。", result.output());
        assertEquals(0, executorCalls.get());
    }

    @Test
    public void execute_expiredAdmissionDeadlineStopsBeforeExecutor() {
        AtomicInteger executorCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime(
                (session, prepareResult) -> {
                    executorCalls.incrementAndGet();
                    return AgentResult.success("不应执行", 1, 1L, List.of());
                },
                () -> "req-expired",
                () -> 3_000L);
        AgentRequest request = createRequest("你好");
        RequestDeadline expired = new RequestDeadline(1_000L, 1_000L);

        RequestSession session = runtime.startSession(request, null, expired);
        RuntimeResult result = runtime.execute(session);

        assertEquals("TIMEOUT", result.errorType());
        assertEquals(0, executorCalls.get());
    }

    // ── Test helpers ──

    private static AgentRequest createRequest(String text) {
        AgentRequest req = new AgentRequest();
        req.setInputType("TEXT");
        req.setText(text);
        return req;
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        throw new AssertionError("Missing span: " + name);
    }
}
