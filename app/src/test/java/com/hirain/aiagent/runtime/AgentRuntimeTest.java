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
import com.hirain.aiagent.toolgroup.ToolGroupSelector;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TestTraceSupport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AgentRuntimeTest {

    @Test
    public void execute_callsExecutorWithNormalizedContext() {
        AtomicReference<String> input = new AtomicReference<>();
        AtomicReference<Map<String, Object>> context = new AtomicReference<>();
        AgentExecutor executor = (userInput, ctx) -> {
            input.set(userInput);
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
        assertSame(traceContext, context.get().get(TraceContext.TRACE_CONTEXT_KEY));
        assertEquals("req-fixed", result.requestId());
        assertEquals(3000L, result.timestampMs());
    }

    @Test
    public void execute_mapsExecutorExceptionToExceptionResult() {
        AgentExecutor failingExecutor = (userInput, ctx) -> {
            throw new RuntimeException("模拟失败");
        };
        AgentRuntime runtime = new AgentRuntime(failingExecutor, () -> "req-fixed", () -> 3000L);

        RuntimeResult result = runtime.execute(runtime.startSession(new AgentRequest(), null));

        assertEquals("EXCEPTION", result.errorType());
        assertNotNull(result.errorDetail());
    }

    @Test
    public void timeoutResult_createsTimeoutResultWithSessionIds() {
        AgentRuntime runtime = new AgentRuntime((userInput, ctx) ->
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
                (userInput, ctx) -> AgentResult.success("完成", 1, 10L, List.of()),
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
    public void startSession_routerExceptionFallsBackToUnknownAndExecuteContinues() {
        IntentRouter router = (text, sourceInputType) -> {
            throw new IllegalStateException("router failed");
        };
        AgentRuntime runtime = new AgentRuntime(
                (userInput, ctx) -> AgentResult.success("继续执行", 1, 10L, List.of()),
                router,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(new AgentRequest(), null);
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
                (userInput, ctx) -> AgentResult.success("ok", 1, 10L, List.of()),
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
                (userInput, ctx) -> {
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

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                session.toolGroupSelectionResult().selectedGroupIds());
        assertTrue(result.success());
        assertFalse(context.get().containsKey("selected_tool_groups"));
        assertFalse(context.get().containsKey("selected_tool_names"));
    }

    @Test
    public void startSession_toolGroupSelectorExceptionFallsBackAndExecuteContinues() {
        ToolGroupSelector failingSelector = (intentResult, userInput) -> {
            throw new IllegalStateException("selector failed");
        };
        AgentRuntime runtime = new AgentRuntime(
                (userInput, ctx) -> AgentResult.success("继续执行", 1, 10L, List.of()),
                (text, sourceInputType) -> IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                        List.of(), text, sourceInputType, "fallback_chat"),
                failingSelector,
                () -> "req-fixed",
                () -> 3000L);

        RequestSession session = runtime.startSession(new AgentRequest(), null);
        RuntimeResult result = runtime.execute(session);

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP),
                session.toolGroupSelectionResult().selectedGroupIds());
        assertEquals("tool_group_selector_exception",
                session.toolGroupSelectionResult().selectionReason());
        assertTrue(session.toolGroupSelectionResult().fallbackUsed());
        assertTrue(result.success());
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

    // ── Test helpers ──

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        throw new AssertionError("Missing span: " + name);
    }
}
