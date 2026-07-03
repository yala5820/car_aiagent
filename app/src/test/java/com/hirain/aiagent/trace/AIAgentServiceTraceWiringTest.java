package com.hirain.aiagent.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.AgentResponse;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
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

public class AIAgentServiceTraceWiringTest {

    @Test
    public void firstResponseWinsAndLaterSuccessDoesNotOverwriteTimeoutRootStatus() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceSession session = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(
                        TraceConfig.builder()
                                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                                .build(),
                        new TraceRedactor()));
        TraceResponseDispatcher dispatcher = new TraceResponseDispatcher(session);
        List<AgentResponse> sent = new ArrayList<>();

        boolean timeoutSent = dispatcher.dispatch(timeoutResponse(), "TIMEOUT", sent::add);
        boolean successSent = dispatcher.dispatch(successResponse(), null, sent::add);
        session.close();

        SpanData dispatchSpan = findSpan(exporter.spans, TraceSpanNames.RESPONSE_DISPATCH);
        SpanData rootSpan = findSpan(exporter.spans, TraceSpanNames.AGENT_REQUEST);
        assertTrue(timeoutSent);
        assertFalse(successSent);
        assertEquals(1, sent.size());
        assertEquals("TIMEOUT", sent.get(0).getErrorType());
        assertEquals(StatusCode.ERROR, rootSpan.getStatus().getStatusCode());
        assertEquals(false, rootSpan.getAttributes()
                .get(AttributeKey.booleanKey(TraceAttributeKeys.RESPONSE_SUCCESS)));
        assertEquals("TIMEOUT", rootSpan.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.RESPONSE_ERROR_TYPE)));
        assertEquals(false, dispatchSpan.getAttributes()
                .get(AttributeKey.booleanKey(TraceAttributeKeys.RESPONSE_SUCCESS)));
        tracerProvider.close();
    }

    @Test
    public void timeoutDispatchClosesRootSpanImmediatelyAndSecondCloseIsNoop() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceSession session = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(
                        TraceConfig.builder()
                                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                                .build(),
                        new TraceRedactor()));
        TraceResponseDispatcher dispatcher = new TraceResponseDispatcher(session);

        boolean timeoutSent = dispatcher.dispatchAndClose(timeoutResponse(), "TIMEOUT", response -> {});
        SpanData rootSpan = findSpan(exporter.spans, TraceSpanNames.AGENT_REQUEST);
        SpanData dispatchSpan = findSpan(exporter.spans, TraceSpanNames.RESPONSE_DISPATCH);
        session.close();

        assertTrue(timeoutSent);
        assertEquals(StatusCode.ERROR, rootSpan.getStatus().getStatusCode());
        assertEquals(false, dispatchSpan.getAttributes()
                .get(AttributeKey.booleanKey(TraceAttributeKeys.RESPONSE_SUCCESS)));
        assertEquals(1, countSpans(exporter.spans, TraceSpanNames.AGENT_REQUEST));
        tracerProvider.close();
    }

    private static AgentResponse timeoutResponse() {
        AgentResponse response = new AgentResponse();
        response.setSuccess(false);
        response.setText("系统: 请求超时");
        response.setErrorType("TIMEOUT");
        return response;
    }

    private static AgentResponse successResponse() {
        AgentResponse response = new AgentResponse();
        response.setSuccess(true);
        response.setText("OK");
        return response;
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        throw new AssertionError("Missing span: " + name);
    }

    private static int countSpans(List<SpanData> spans, String name) {
        int count = 0;
        for (SpanData span : spans) {
            if (name.equals(span.getName())) count++;
        }
        return count;
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
