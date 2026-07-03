package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class TestTraceSupport {

    private TestTraceSupport() {
    }

    public static TestSession redactedSession() {
        return new TestSession();
    }

    public static final class TestSession {
        public final CapturingExporter exporter = new CapturingExporter();
        private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        private final Tracer tracer = tracerProvider.get("test");
        public final TraceSession traceSession = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(
                        TraceConfig.builder()
                                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                                .build(),
                        new TraceRedactor()));

        public void close() {
            traceSession.close();
            tracerProvider.close();
        }
    }

    public static final class CapturingExporter implements SpanExporter {
        public final List<SpanData> spans = new ArrayList<>();

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
