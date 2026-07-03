package com.hirain.aiagent.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TraceSessionRootTest {

    @Test
    public void exposesTracerAndWriterAndIgnoresNullStringAttributes() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceAttributeWriter writer = new TraceAttributeWriter(
                TraceConfig.builder().contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED).build(),
                new TraceRedactor());

        Span root = tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan();
        TraceSession session = new TraceSession(root, tracer, writer);

        session.setAttribute(TraceAttributeKeys.REQUEST_ID, "req-42");
        session.setAttribute(TraceAttributeKeys.SOURCE_APP, (String) null);
        session.close();

        SpanData data = exporter.spans.get(0);
        assertEquals(tracer, session.tracer());
        assertEquals(writer, session.writer());
        assertEquals("req-42", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.REQUEST_ID)));
        assertNull(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.SOURCE_APP)));
        tracerProvider.close();
    }

    @Test
    public void startChildSpanCreatesNamedSpan() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceSession session = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(TraceConfig.builder().build(), new TraceRedactor()));

        Span child = session.startChildSpan(TraceSpanNames.RESPONSE_DISPATCH);
        child.end();
        session.close();

        assertEquals(TraceSpanNames.RESPONSE_DISPATCH, exporter.spans.get(0).getName());
        tracerProvider.close();
    }

    @Test
    public void childSpanKeepsRootParentWhenStartedOnDifferentThread() throws Exception {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");
        TraceSession session = new TraceSession(
                tracer.spanBuilder(TraceSpanNames.AGENT_REQUEST).startSpan(),
                tracer,
                new TraceAttributeWriter(TraceConfig.builder().build(), new TraceRedactor()));
        String rootSpanId = session.toTraceContext().sessionSpanId();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                Span child = session.startChildSpan("worker.child");
                child.end();
            }).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        session.close();

        SpanData child = findSpan(exporter.spans, "worker.child");
        assertEquals(session.traceId(), child.getTraceId());
        assertEquals(rootSpanId, child.getParentSpanId());
        tracerProvider.close();
    }

    @Test
    public void traceManagerExposesAgentRequestSessionFactory() throws Exception {
        TraceManager.class.getDeclaredMethod(
                "startAgentRequest",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class);
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        throw new AssertionError("Missing span: " + name);
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
