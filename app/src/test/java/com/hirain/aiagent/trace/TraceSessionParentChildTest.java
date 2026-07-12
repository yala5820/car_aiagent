package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 TraceSession 的 parent-aware span 创建 API。
 * <p>
 * 测试 root → agent.loop → child 三级层级，直接断言 parentSpanId。
 */
public class TraceSessionParentChildTest {

    private SdkTracerProvider tracerProvider;
    private CapturingExporter exporter;
    private Tracer tracer;

    private static final class CapturingExporter implements io.opentelemetry.sdk.trace.export.SpanExporter {
        final List<SpanData> spans = new ArrayList<>();

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode export(
                java.util.Collection<SpanData> collection) {
            spans.addAll(collection);
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode flush() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
        }
    }

    @Before
    public void setUp() {
        exporter = new CapturingExporter();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = tracerProvider.get("test");
    }

    @After
    public void tearDown() {
        tracerProvider.close();
    }

    @Test
    public void explicitParent_childSpanIdMatchesParent() {
        Span rootSpan = tracer.spanBuilder("agent.request").startSpan();
        Context rootContext = Context.current().with(rootSpan);
        String rootSpanId = rootSpan.getSpanContext().getSpanId();

        TraceSession session = new TraceSession(rootSpan, tracer);
        Span childSpan = session.startChildSpan("agent.loop", rootContext);

        // span 必须先 end 才能 exporter export
        childSpan.end();
        rootSpan.end();
        session.close();

        // Find the exported spans
        SpanData rootData = exporter.spans.stream()
                .filter(s -> s.getName().equals("agent.request"))
                .findFirst().orElse(null);
        SpanData childData = exporter.spans.stream()
                .filter(s -> s.getName().equals("agent.loop"))
                .findFirst().orElse(null);

        assertNotNull("Root span should exist", rootData);
        assertNotNull("Child span should exist", childData);
        assertEquals("Child's parentSpanId should match root's spanId",
                rootData.getSpanId(), childData.getParentSpanId());
    }

    @Test
    public void defaultParent_childSpanStillHasRootParent() {
        Span rootSpan = tracer.spanBuilder("agent.request").startSpan();
        TraceSession session = new TraceSession(rootSpan, tracer);

        // No explicit parent → should default to rootContext
        Span childSpan = session.startChildSpan("child.no.parent");

        childSpan.end();
        rootSpan.end();
        session.close();

        SpanData rootData = exporter.spans.stream()
                .filter(s -> s.getName().equals("agent.request"))
                .findFirst().orElse(null);
        SpanData childData = exporter.spans.stream()
                .filter(s -> s.getName().equals("child.no.parent"))
                .findFirst().orElse(null);

        assertNotNull("Root span should exist", rootData);
        assertNotNull("Child span should exist", childData);
        assertEquals("Child's parentSpanId should match root's spanId",
                rootData.getSpanId(), childData.getParentSpanId());
    }

    @Test
    public void scopeClose_parentEndsBeforeChild_childStillExported() {
        Span rootSpan = tracer.spanBuilder("agent.request").startSpan();
        Context rootContext = Context.current().with(rootSpan);
        TraceSession session = new TraceSession(rootSpan, tracer);

        Span childSpan = session.startChildSpan("child.before.parent.ends", rootContext);
        childSpan.end();
        session.close(); // This ends rootSpan

        // After root span ends, child should still be available
        SpanData rootData = exporter.spans.stream()
                .filter(s -> s.getName().equals("agent.request"))
                .findFirst().orElse(null);
        SpanData childData = exporter.spans.stream()
                .filter(s -> s.getName().equals("child.before.parent.ends"))
                .findFirst().orElse(null);

        assertNotNull("Root span should be exported", rootData);
        assertNotNull("Child span should be exported even after root ends", childData);
        assertEquals("Child's parentSpanId should match root's spanId",
                rootData.getSpanId(), childData.getParentSpanId());
    }

    @Test
    public void startChildSpan_nullParentDefaultsToRoot() {
        Span rootSpan = tracer.spanBuilder("agent.request").startSpan();
        TraceSession session = new TraceSession(rootSpan, tracer);

        Span childSpan = session.startChildSpan("child.null.parent", (Context) null);

        childSpan.end();
        rootSpan.end();
        session.close();

        SpanData rootData = exporter.spans.stream()
                .filter(s -> s.getName().equals("agent.request"))
                .findFirst().orElse(null);
        SpanData childData = exporter.spans.stream()
                .filter(s -> s.getName().equals("child.null.parent"))
                .findFirst().orElse(null);

        assertNotNull("Root span should exist", rootData);
        assertNotNull("Child span should exist", childData);
        assertEquals("Null parent should default to root",
                rootData.getSpanId(), childData.getParentSpanId());
    }
}
