package com.hirain.aiagent.trace;

import com.hirain.aiagent.safety.SafetyDecision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Phase 3 Tool 内部阶段 span 层次与属性。
 * <p>
 * 测试目标：
 * 1. 正常工具执行：tool.execute → tool.safety_check → tool.dispatch → tool.result_writeback
 * 2. 安全拒绝：tool.execute → tool.safety_check（decision=DENY），无 tool.dispatch
 * 3. dispatch 异常：tool.dispatch 标记 dispatch_success=false
 */
public class ToolPhaseTraceTest {

    @Test
    public void normalTool_haveAllThreePhases() {
        TestSession ts = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(ts.traceSession);

        Span toolSpan = recorder.startTool(
                ToolExecutionRequest.builder().id("call-1").name("set_ac_temperature")
                        .arguments("{\"temp\":24}").build(),
                0);
        io.opentelemetry.context.Scope scope = toolSpan.makeCurrent();

        // Stage 1: safety_check (allow)
        Span safety = recorder.startToolSafetyCheck();
        recorder.finishToolSafetyCheck(safety, SafetyDecision.allow());

        // Stage 2: dispatch (success) — 增强版 finishToolDispatch
        Span dispatch = recorder.startToolDispatch("set_ac_temperature");
        recorder.finishToolDispatch(dispatch, true, "VehicleAcManager", "setAcTemperature",
                50, true, true);

        // Stage 3: writeback
        Span writeback = recorder.startToolWriteback();
        recorder.finishTool(toolSpan, "{\"code\":0}", SafetyDecision.allow());
        recorder.finishToolWriteback(writeback, true);

        scope.close();
        toolSpan.end();
        ts.close();

        // Verify hierarchy
        SpanData toolData = findSpan(ts.exporter.spans, "tool.execute");
        assertNotNull("tool.execute span should exist", toolData);

        SpanData safetyData = findSpan(ts.exporter.spans, "tool.safety_check");
        assertNotNull("tool.safety_check span should exist", safetyData);
        assertEquals("safety_check parent should be tool.execute",
                toolData.getSpanId(), safetyData.getParentSpanId());
        assertEquals("ALLOW", safetyData.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_DECISION)));
        assertEquals("ALLOW", safetyData.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_REASON_CODE)));

        SpanData dispatchData = findSpan(ts.exporter.spans, "tool.dispatch");
        assertNotNull("tool.dispatch span should exist", dispatchData);
        assertEquals("dispatch parent should be tool.execute",
                toolData.getSpanId(), dispatchData.getParentSpanId());
        assertTrue("dispatch should have duration_ms",
                dispatchData.getAttributes().get(AttributeKey.longKey("tool.dispatch_duration_ms")) > 0);
        assertTrue("dispatch should have success=true",
                dispatchData.getAttributes().get(AttributeKey.booleanKey("tool.dispatch_success")));

        SpanData writebackData = findSpan(ts.exporter.spans, "tool.result_writeback");
        assertNotNull("tool.result_writeback span should exist", writebackData);
        assertEquals("writeback parent should be tool.execute",
                toolData.getSpanId(), writebackData.getParentSpanId());
        assertTrue("writeback should have written_to_memory=true",
                writebackData.getAttributes().get(AttributeKey.booleanKey("tool.writeback_to_memory")));
        // 正常路径下 tool.success=true
        assertTrue("tool.execute should have success=true (normal path)",
                toolData.getAttributes().get(AttributeKey.booleanKey("tool.success")));

        // Package 3: 验证增强型 dispatch 属性
        assertEquals("VehicleAcManager",
                dispatchData.getAttributes().get(AttributeKey.stringKey("tool.target_class")));
        assertEquals("setAcTemperature",
                dispatchData.getAttributes().get(AttributeKey.stringKey("tool.target_method")));
        assertTrue("dispatch argument_parse_success should be true",
                dispatchData.getAttributes().get(AttributeKey.booleanKey("tool.argument_parse_success")));
        assertTrue("dispatch invoke_success should be true",
                dispatchData.getAttributes().get(AttributeKey.booleanKey("tool.invoke_success")));
    }

    @Test
    public void deniedTool_noDispatchSpan() {
        TestSession ts = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(ts.traceSession);

        Span toolSpan = recorder.startTool(
                ToolExecutionRequest.builder().id("call-1").name("set_door_lock")
                        .arguments("{\"lock\":true}").build(),
                0);
        io.opentelemetry.context.Scope scope = toolSpan.makeCurrent();

        // Stage 1: safety_check (denied)
        Span safety = recorder.startToolSafetyCheck();
        SafetyDecision denied = SafetyDecision.deny(
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED,
                "speed too high");
        recorder.finishToolSafetyCheck(safety, denied);

        // DENY — no dispatch
        recorder.finishTool(toolSpan,
                "[SAFETY_DENY][DOOR_UNLOCK_REQUIRES_STOPPED] speed too high",
                denied);

        scope.close();
        toolSpan.end();
        ts.close();

        SpanData toolData = findSpan(ts.exporter.spans, "tool.execute");
        assertNotNull("tool.execute span should exist", toolData);

        SpanData safetyData = findSpan(ts.exporter.spans, "tool.safety_check");
        assertNotNull("tool.safety_check should exist", safetyData);
        assertEquals("DENY", safetyData.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_DECISION)));
        assertEquals("DOOR_UNLOCK_REQUIRES_STOPPED", safetyData.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_REASON_CODE)));
        assertEquals("deny reason should match",
                "speed too high",
                safetyData.getAttributes().get(
                        AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_REASON)));

        SpanData dispatchData = findSpan(ts.exporter.spans, "tool.dispatch");
        assertNull("tool.dispatch should NOT exist when denied", dispatchData);
    }

    @Test
    public void dispatchException_dispatchSpanMarkedError() {
        TestSession ts = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(ts.traceSession);

        Span toolSpan = recorder.startTool(
                ToolExecutionRequest.builder().id("call-1").name("set_ac_temperature")
                        .arguments("{\"temp\":24}").build(),
                0);
        io.opentelemetry.context.Scope scope = toolSpan.makeCurrent();

        // Stage 1: safety_check (allow)
        Span safety = recorder.startToolSafetyCheck();
        recorder.finishToolSafetyCheck(safety, SafetyDecision.allow());

        // Stage 2: dispatch (exception) — 增强版 finishToolDispatch
        Span dispatch = recorder.startToolDispatch("set_ac_temperature");
        recorder.finishToolDispatch(dispatch, false, "VehicleAcManager", "setAcTemperature",
                0, false, false);

        // Stage 3: writeback (error result)
        Span writeback = recorder.startToolWriteback();
        recorder.finishTool(toolSpan, "error: timeout", null);
        recorder.finishToolWriteback(writeback, true);

        scope.close();
        toolSpan.end();
        ts.close();

        SpanData dispatchData = findSpan(ts.exporter.spans, "tool.dispatch");
        assertNotNull("tool.dispatch should exist", dispatchData);
        assertFalse("dispatch should have success=false",
                dispatchData.getAttributes().get(AttributeKey.booleanKey("tool.dispatch_success")));

        SpanData writebackData = findSpan(ts.exporter.spans, "tool.result_writeback");
        assertNotNull("tool.result_writeback should exist", writebackData);

        // P0 验证：异常路径下 tool.execute 必须显示失败
        SpanData toolData = findSpan(ts.exporter.spans, "tool.execute");
        assertNotNull("tool.execute should exist", toolData);
        assertFalse("tool.execute should have success=false (verdict=null)",
                toolData.getAttributes().get(AttributeKey.booleanKey("tool.success")));
    }

    @Test
    public void multiTool_eachToolHasOwnPhases() {
        TestSession ts = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(ts.traceSession);

        // 创建 agent.iteration 作为工具调用的父容器
        Span iterSpan = ts.traceSession.tracer()
                .spanBuilder("agent.iteration").startSpan();
        io.opentelemetry.context.Scope iterScope = iterSpan.makeCurrent();

        // Tool 1: set_ac_temperature
        Span tool1Span = recorder.startTool(
                ToolExecutionRequest.builder().id("call-1").name("set_ac_temperature")
                        .arguments("{\"temp\":24}").build(),
                0, io.opentelemetry.context.Context.current());
        io.opentelemetry.context.Scope tool1Scope = tool1Span.makeCurrent();
        Span s1 = recorder.startToolSafetyCheck();
        recorder.finishToolSafetyCheck(s1, SafetyDecision.allow());
        Span d1 = recorder.startToolDispatch("set_ac_temperature");
        recorder.finishToolDispatch(d1, true, "VehicleAcManager", "setAcTemperature", 30, true, true);
        Span w1 = recorder.startToolWriteback();
        recorder.finishTool(tool1Span, "{\"code\":0}", SafetyDecision.allow());
        recorder.finishToolWriteback(w1, true);
        tool1Scope.close();
        tool1Span.end();

        // Tool 2: set_door_lock
        Span tool2Span = recorder.startTool(
                ToolExecutionRequest.builder().id("call-2").name("set_door_lock")
                        .arguments("{\"lock\":false}").build(),
                0, io.opentelemetry.context.Context.current());
        io.opentelemetry.context.Scope tool2Scope = tool2Span.makeCurrent();
        Span s2 = recorder.startToolSafetyCheck();
        recorder.finishToolSafetyCheck(s2, SafetyDecision.allow());
        Span d2 = recorder.startToolDispatch("set_door_lock");
        recorder.finishToolDispatch(d2, true, "VehicleDoorManager", "setDoorLock", 20, true, true);
        Span w2 = recorder.startToolWriteback();
        recorder.finishTool(tool2Span, "{\"code\":0}", SafetyDecision.allow());
        recorder.finishToolWriteback(w2, true);
        tool2Scope.close();
        tool2Span.end();

        iterScope.close();
        iterSpan.end();
        ts.close();

        // 验证 Tool 1 的三阶段
        SpanData t1Data = findSpan(ts.exporter.spans, "tool.execute");
        assertNotNull("tool1.execute should exist", t1Data);
        assertEquals("tool1 parent should be agent.iteration",
                iterSpan.getSpanContext().getSpanId(), t1Data.getParentSpanId());

        // 验证每个工具都存在三个子阶段
        assertNotNull("tool1.safety_check should exist",
                findSpan(ts.exporter.spans, "tool.safety_check"));
        assertNotNull("tool1.dispatch should exist",
                findSpan(ts.exporter.spans, "tool.dispatch"));
        assertNotNull("tool1.result_writeback should exist",
                findSpan(ts.exporter.spans, "tool.result_writeback"));

        // 验证两个 tool.execute 是不同 span（区分多工具）
        List<SpanData> executeSpans = findAllSpans(ts.exporter.spans, "tool.execute");
        assertEquals("two tool.execute spans should exist", 2, executeSpans.size());
        assertNotNull("tool1 dispatch should have target",
                findSpan(ts.exporter.spans, "tool.dispatch"));
    }

    @Test
    public void toolExecution_thenSecondIteration() {
        TestSession ts = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(ts.traceSession);

        // Iteration 0: 工具调用
        Span iter0Span = ts.traceSession.tracer()
                .spanBuilder("agent.iteration").startSpan();
        io.opentelemetry.context.Scope iter0Scope = iter0Span.makeCurrent();

        Span toolSpan = recorder.startTool(
                ToolExecutionRequest.builder().id("call-1").name("set_ac_temperature")
                        .arguments("{\"temp\":24}").build(),
                0, io.opentelemetry.context.Context.current());
        io.opentelemetry.context.Scope toolScope = toolSpan.makeCurrent();
        Span s = recorder.startToolSafetyCheck();
        recorder.finishToolSafetyCheck(s, SafetyDecision.allow());
        Span d = recorder.startToolDispatch("set_ac_temperature");
        recorder.finishToolDispatch(d, true, "VehicleAcManager", "setAcTemperature", 50, true, true);
        Span w = recorder.startToolWriteback();
        recorder.finishTool(toolSpan, "{\"code\":0}", SafetyDecision.allow());
        recorder.finishToolWriteback(w, true);
        toolScope.close();
        toolSpan.end();

        iter0Scope.close();
        iter0Span.end();

        // Iteration 1: LLM 直接返回文本（无工具调用）
        Span iter1Span = ts.traceSession.tracer()
                .spanBuilder("agent.iteration").startSpan();
        io.opentelemetry.context.Scope iter1Scope = iter1Span.makeCurrent();

        Span llmSpan = recorder.startLlmCall("qwen-turbo", 1, 1,
                io.opentelemetry.context.Context.current());
        recorder.enrichLlmResponse(llmSpan,
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("已为您调整"))
                        .build());
        llmSpan.end();

        iter1Scope.close();
        iter1Span.end();
        ts.close();

        // 验证两个 iteration span 都存在
        List<SpanData> iterSpans = findAllSpans(ts.exporter.spans, "agent.iteration");
        assertEquals("two agent.iteration spans should exist", 2, iterSpans.size());

        // 验证 tool.execute 属于 iteration[0]
        SpanData toolData = findSpan(ts.exporter.spans, "tool.execute");
        assertNotNull("tool.execute should exist", toolData);
        assertEquals("tool.execute parent should be agent.iteration[0]",
                iter0Span.getSpanContext().getSpanId(), toolData.getParentSpanId());

        // 验证 gen_ai.chat 属于 iteration[1]
        SpanData llmData = findSpan(ts.exporter.spans, "gen_ai.chat");
        assertNotNull("gen_ai.chat should exist", llmData);
        assertEquals("gen_ai.chat parent should be agent.iteration[1]",
                iter1Span.getSpanContext().getSpanId(), llmData.getParentSpanId());
    }

    // ── 辅助 ──

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        return null;
    }

    private static List<SpanData> findAllSpans(List<SpanData> spans, String name) {
        List<SpanData> result = new ArrayList<>();
        for (SpanData span : spans) {
            if (name.equals(span.getName())) result.add(span);
        }
        return result;
    }

    private static final class TestSession {
        private final CapturingExporter exporter = new CapturingExporter();
        private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        private final io.opentelemetry.api.trace.Tracer tracer = tracerProvider.get("test");
        private final TraceSession traceSession = new TraceSession(
                tracer.spanBuilder("agent.request").startSpan(),
                tracer,
                new TraceAttributeWriter(
                        TraceConfig.builder()
                                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                                .build(),
                        new TraceRedactor()));

        private void close() {
            traceSession.close();
            tracerProvider.close();
        }
    }

    private static final class CapturingExporter implements SpanExporter {
        final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
