package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Span;

public class TraceAttributeWriter {

    private static final int TEXT_MAX_LENGTH = 500;
    private static final int ARGUMENT_MAX_LENGTH = 200;
    private static final int RESULT_MAX_LENGTH = 200;

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
            output = truncate(value, maxLength(kind), suffix(kind));
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

    private static int maxLength(ContentKind kind) {
        return switch (kind) {
            case TEXT -> TEXT_MAX_LENGTH;
            case ARGUMENT -> ARGUMENT_MAX_LENGTH;
            case RESULT -> RESULT_MAX_LENGTH;
        };
    }

    private static String suffix(ContentKind kind) {
        return kind == ContentKind.ARGUMENT ? "... (truncated)" : "... (truncated)";
    }

    private static String truncate(String value, int maxLength, String suffix) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + suffix;
    }

    private enum ContentKind {
        TEXT,
        ARGUMENT,
        RESULT
    }
}
