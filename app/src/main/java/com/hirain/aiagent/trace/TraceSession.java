package com.hirain.aiagent.trace;

import android.util.Log;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * 单次用户请求的 trace 生命周期管理器（{@link AutoCloseable}）。
 * <p>
 * 封装 root Span + 传递 trace 元数据。一个 session 内部可创建
 * LLM span、TOOL span 等子 span。
 */
public class TraceSession implements AutoCloseable {

    private static final String TAG = "TraceSession";

    private final Span rootSpan;
    private final String traceId;
    private final Tracer tracer;
    private final Context parentContext;

    TraceSession(Span rootSpan, Tracer tracer) {
        this.rootSpan = rootSpan;
        this.traceId = rootSpan.getSpanContext().getTraceId();
        this.tracer = tracer;
        this.parentContext = rootSpan.storeInContext(Context.current());
    }

    // ── 子 span 创建 ──

    /** 创建 LLM 调用子 span（遵循 OpenInference 语义） */
    public Span startLlmSpan(String modelName, int inputMessageCount) {
        Span span = tracer.spanBuilder("llm.call")
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setParent(parentContext)
                .startSpan();
        span.setAttribute("llm.model_name", modelName);
        span.setAttribute("llm.input_messages.count", inputMessageCount);
        return span;
    }

    /** 创建工具执行子 span */
    public Span startToolSpan(String toolName, String arguments) {
        Span span = tracer.spanBuilder("tool.execute")
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setParent(parentContext)
                .startSpan();
        span.setAttribute("tool.name", toolName);
        if (arguments != null) {
            String args = arguments.length() > 200
                    ? arguments.substring(0, 200) + "… (truncated)"
                    : arguments;
            span.setAttribute("tool.parameters", args);
        }
        return span;
    }

    // ── Session 生命周期 ──

    /** 标记 session 成功/失败 */
    public void setStatus(boolean success, String errorMessage) {
        if (success) {
            rootSpan.setStatus(StatusCode.OK);
        } else {
            rootSpan.setStatus(StatusCode.ERROR, errorMessage != null ? errorMessage : "Unknown error");
        }
    }

    /** 写入通用 attribute */
    public void setAttribute(String key, String value) {
        rootSpan.setAttribute(key, value);
    }

    public void setAttribute(String key, long value) {
        rootSpan.setAttribute(key, value);
    }

    /** 关闭 session（刷新 span） */
    @Override
    public void close() {
        try {
            rootSpan.end();
        } catch (Exception e) {
            Log.w(TAG, "Failed to end trace span", e);
        }
    }

    // ── 读取器 ──

    public String traceId() { return traceId; }

    public TraceContext toTraceContext() {
        return new TraceContext(traceId, rootSpan.getSpanContext().getSpanId());
    }
}
