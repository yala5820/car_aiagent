package com.hirain.aiagent.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.safety.SafetyDecision;

import dev.langchain4j.agent.tool.ToolSpecification;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;

public class AgentTraceRecorderTest {

    @Test
    public void recordsPromptAssemblyWithRedactedMessages() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);

        Span span = recorder.startPromptAssembly(
                "chat",
                2,
                List.of(UserMessage.from("手机号 13812345678")),
                List.of(UserMessage.from("打开空调")),
                2,
                List.of("set_ac_temperature"));
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.PROMPT_ASSEMBLY, data.getName());
        String messages = data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.PROMPT_TRANSIENT_MESSAGES));
        assertFalse(messages.contains("13812345678"));
        assertTrue(messages.contains("138****5678"));
        // 旧方法（不含 parent 参数）仍产生 root-parented span
        assertNotNull("Old method should produce root-parented span (non-null parentSpanId)",
                data.getParentSpanId());
    }

    @Test
    public void recordsLlmCallAndResponse() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);

        Span span = recorder.startLlmCall("qwen-turbo", 1, 3);
        recorder.enrichLlmResponse(span, ChatResponse.builder()
                .aiMessage(AiMessage.from("已为你打开空调"))
                .build());
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.GEN_AI_CHAT, data.getName());
        assertEquals("qwen-turbo", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.GEN_AI_MODEL)));
        assertEquals("dashscope", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.GEN_AI_PROVIDER)));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.GEN_AI_OUTPUT))
                .contains("已为你打开空调"));
    }

    @Test
    public void recordsToolResultAndSafetyDecision() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("set_door_lock")
                .arguments("{\"phone\":\"13812345678\"}")
                .build();

        Span span = recorder.startTool(request, 0);
        recorder.finishTool(span, "车速过高，禁止开门", SafetyDecision.deny(
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED,
                "speed too high"));
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.TOOL_EXECUTE, data.getName());
        assertEquals("set_door_lock", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.TOOL_NAME)));
        assertEquals(false, data.getAttributes().get(AttributeKey.booleanKey(TraceAttributeKeys.TOOL_SUCCESS)));
        assertEquals("DENY", data.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_DECISION)));
        assertEquals("DOOR_UNLOCK_REQUIRES_STOPPED", data.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_REASON_CODE)));
        assertEquals("speed too high", data.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_REASON)));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.TOOL_ARGUMENTS))
                .contains("138****5678"));
    }

    @Test
    public void allowedButDispatchFailed_marksToolAsFailed() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("failed-dispatch")
                .name("set_ac_status")
                .arguments("{\"arg0\":true}")
                .build();

        Span span = recorder.startTool(request, 0);
        recorder.finishTool(span, "工具执行失败: 参数错误",
                SafetyDecision.allow(), false);
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(false, data.getAttributes().get(
                AttributeKey.booleanKey(TraceAttributeKeys.TOOL_SUCCESS)));
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                data.getStatus().getStatusCode());
        assertEquals("ALLOW", data.getAttributes().get(
                AttributeKey.stringKey(TraceAttributeKeys.TOOL_SAFETY_DECISION)));
    }

    @Test
    public void recordsMemoryExtractWithRedactedPromptAndOutput() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);

        Span span = recorder.startMemory("extract", 80);
        recorder.finishMemoryExtract(
                span,
                "用户手机号 13812345678，喜欢 24 度",
                "[{\"category\":\"preference\",\"key\":\"phone\",\"value\":\"13812345678\"}]",
                1);
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_EXTRACT, data.getName());
        assertEquals("extract", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OPERATION)));
        assertEquals(80L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_INPUT_CHARS)).longValue());
        assertEquals(1L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_CANDIDATE_COUNT)).longValue());
        assertFalse(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_PROMPT))
                .contains("13812345678"));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_PROMPT))
                .contains("138****5678"));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OUTPUT))
                .contains("138****5678"));
    }

    @Test
    public void recordsMemoryCompressDecisionAndSummary() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);

        Span span = recorder.startMemory("compress", 120);
        recorder.finishMemoryCompress(span, true,
                "摘要手机号 13812345678 的历史对话",
                "用户喜欢 24 度空调");
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_COMPRESS, data.getName());
        assertEquals("compress", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OPERATION)));
        assertEquals(true, data.getAttributes().get(AttributeKey.booleanKey(TraceAttributeKeys.MEMORY_COMPRESSED)));
        assertEquals(120L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_INPUT_CHARS)).longValue());
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OUTPUT))
                .contains("用户喜欢 24 度空调"));
        assertTrue(data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_OUTPUT_CHARS)) > 0);
    }

    @Test
    public void recordLlmRequest_includesCompleteToolSchema() {
        TestSession session = new TestSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);

        Span span = recorder.startLlmCall("qwen-turbo", 1, 2);

        ToolSpecification spec1 = ToolSpecification.builder()
                .name("get_weather")
                .description("获取天气信息")
                .build();
        // 带 parameters 的 tool spec
        ToolSpecification spec2 = ToolSpecification.builder()
                .name("set_ac_temperature")
                .description("设置空调温度")
                .build();

        recorder.recordLlmRequest(span, "qwen-turbo", 1,
                List.of(dev.langchain4j.data.message.UserMessage.from("打开空调")),
                List.of(spec1, spec2));
        span.end();
        session.close();

        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.GEN_AI_CHAT, data.getName());

        String toolSpecs = data.getAttributes()
                .get(AttributeKey.stringKey("gen_ai.request.tool_specs"));
        assertNotNull("tool_specs should exist", toolSpecs);
        assertTrue("tool_specs should contain name='get_weather'",
                toolSpecs.contains("name=get_weather"));
        assertTrue("tool_specs should contain description='获取天气信息'",
                toolSpecs.contains("description=获取天气信息"));
        assertTrue("tool_specs should contain tool count separator '---'",
                toolSpecs.contains("---"));

        String messages = data.getAttributes()
                .get(AttributeKey.stringKey("gen_ai.request.messages"));
        assertNotNull("request messages should exist", messages);
        assertTrue("messages should contain user message",
                messages.contains("打开空调"));
    }

    private static final class TestSession {
        private final CapturingExporter exporter = new CapturingExporter();
        private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        private final Tracer tracer = tracerProvider.get("test");
        private final TraceSession traceSession = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
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
        private final List<SpanData> spans = new ArrayList<>();

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
