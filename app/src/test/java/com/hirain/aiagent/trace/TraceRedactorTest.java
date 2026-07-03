package com.hirain.aiagent.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

public class TraceRedactorTest {

    @Test
    public void redactUserInputMasksPhoneNumberWithoutKeepingOriginal() {
        TraceRedactor redactor = new TraceRedactor();

        String result = redactor.redactUserInput("请联系 13812345678 确认行程");

        assertFalse(result.contains("13812345678"));
        assertTrue(result.contains("138****5678"));
    }

    @Test
    public void redactUserInputTruncatesLongText() {
        TraceRedactor redactor = new TraceRedactor();
        String input = repeat("a", 520);

        String result = redactor.redactUserInput(input);

        assertTrue(result.length() < input.length());
        assertTrue(result.contains("truncated"));
    }

    @Test
    public void redactArgumentsTruncatesBase64Payload() {
        TraceRedactor redactor = new TraceRedactor();
        String input = "{\"image\":\"data:image/png;base64,iVBOR" + repeat("A", 260) + "\"}";

        String result = redactor.redactArguments(input);

        assertTrue(result.length() < input.length());
        assertTrue(result.contains("base64 truncated"));
    }

    @Test
    public void writerSkipsContentAttributesWhenCaptureModeIsOff() {
        TraceConfig config = TraceConfig.builder()
                .contentCaptureMode(TraceConfig.ContentCaptureMode.OFF)
                .build();
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");

        Span span = tracer.spanBuilder("test").startSpan();
        TraceAttributeWriter writer = new TraceAttributeWriter(config, new TraceRedactor());
        writer.putText(span, TraceAttributeKeys.USER_INPUT, "手机号 13812345678");
        writer.putString(span, TraceAttributeKeys.REQUEST_ID, "req-1");
        span.end();

        SpanData data = exporter.spans.get(0);
        assertNull(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.USER_INPUT)));
        assertEquals("req-1", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.REQUEST_ID)));
        tracerProvider.close();
    }

    @Test
    public void writerRedactsContentAttributesInRedactedMode() {
        TraceConfig config = TraceConfig.builder()
                .contentCaptureMode(TraceConfig.ContentCaptureMode.REDACTED)
                .build();
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");

        Span span = tracer.spanBuilder("test").startSpan();
        TraceAttributeWriter writer = new TraceAttributeWriter(config, new TraceRedactor());
        writer.putText(span, TraceAttributeKeys.USER_INPUT, "手机号 13812345678");
        span.end();

        String value = exporter.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.USER_INPUT));
        assertFalse(value.contains("13812345678"));
        assertTrue(value.contains("138****5678"));
        tracerProvider.close();
    }

    @Test
    public void writerKeepsContentUnredactedInFullDebugMode() {
        TraceConfig config = TraceConfig.builder()
                .contentCaptureMode(TraceConfig.ContentCaptureMode.FULL_DEBUG)
                .build();
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = tracerProvider.get("test");

        Span span = tracer.spanBuilder("test").startSpan();
        TraceAttributeWriter writer = new TraceAttributeWriter(config, new TraceRedactor());
        writer.putText(span, TraceAttributeKeys.USER_INPUT, "手机号 13812345678");
        span.end();

        String value = exporter.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.USER_INPUT));
        assertTrue(value.contains("13812345678"));
        tracerProvider.close();
    }

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
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
