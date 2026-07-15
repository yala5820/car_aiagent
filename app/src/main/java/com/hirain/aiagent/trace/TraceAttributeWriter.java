package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Span;

public class TraceAttributeWriter {

    private final TraceConfig config;
    private final TraceRedactor redactor;

    public TraceAttributeWriter(TraceConfig config, TraceRedactor redactor) {
        this.config = config != null ? config : TraceConfig.production();
        this.redactor = redactor != null ? redactor : new TraceRedactor();
    }

    public void putText(Span span, String key, String value) {
        putContent(span, key, value, ContentKind.TEXT);
    }

    public void putArgument(Span span, String key, String value) {
        putContent(span, key, value, ContentKind.ARGUMENT);
    }

    public void putResult(Span span, String key, String value) {
        putContent(span, key, value, ContentKind.RESULT);
    }

    public void putString(Span span, String key, String value) {
        if (span == null || key == null || value == null) return;
        span.setAttribute(key, value);
    }

    public void putLong(Span span, String key, long value) {
        if (span == null || key == null) return;
        span.setAttribute(key, value);
    }

    public void putBoolean(Span span, String key, boolean value) {
        if (span == null || key == null) return;
        span.setAttribute(key, value);
    }

    private void putContent(Span span, String key, String value, ContentKind kind) {
        if (span == null || key == null || value == null) return;
        if (config.contentCaptureMode() == TraceConfig.ContentCaptureMode.OFF) return;

        String output;
        if (config.contentCaptureMode() == TraceConfig.ContentCaptureMode.REDACTED) {
            output = redact(value, kind);
        } else {
            // Demo 的 FULL_DEBUG 用于还原真实执行过程，业务层不得截断正文。
            output = value;
        }
        span.setAttribute(key, output);
    }

    private String redact(String value, ContentKind kind) {
        return switch (kind) {
            case TEXT -> redactor.redactUserInput(value);
            case ARGUMENT -> redactor.redactArguments(value);
            case RESULT -> redactor.redactResult(value);
        };
    }

    private enum ContentKind {
        TEXT,
        ARGUMENT,
        RESULT
    }
}
