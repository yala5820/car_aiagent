package com.hirain.aiagent.trace;

import android.util.Log;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

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
    private final TraceAttributeWriter writer;
    private final Context rootContext;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    TraceSession(Span rootSpan, Tracer tracer) {
        this(rootSpan, tracer, new TraceAttributeWriter(TraceConfig.production(), new TraceRedactor()));
    }

    TraceSession(Span rootSpan, Tracer tracer, TraceAttributeWriter writer) {
        this.rootSpan = rootSpan;
        this.traceId = rootSpan.getSpanContext().getTraceId();
        this.tracer = tracer;
        this.writer = writer != null
                ? writer
                : new TraceAttributeWriter(TraceConfig.production(), new TraceRedactor());
        this.rootContext = Context.current().with(rootSpan);
    }

    // ── 子 span 创建 ──

    /** 创建 LLM 调用子 span（遵循 OpenInference 语义） */
    public Span startLlmSpan(String modelName, int inputMessageCount) {
        Span span = tracer.spanBuilder(TraceSpanNames.GEN_AI_CHAT)
                .setParent(rootContext)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
        writer.putString(span, TraceAttributeKeys.GEN_AI_MODEL, modelName);
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT, inputMessageCount);
        return span;
    }

    /** 创建工具执行子 span */
    public Span startToolSpan(String toolName, String arguments) {
        Span span = tracer.spanBuilder(TraceSpanNames.TOOL_EXECUTE)
                .setParent(rootContext)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
        writer.putString(span, TraceAttributeKeys.TOOL_NAME, toolName);
        writer.putArgument(span, TraceAttributeKeys.TOOL_ARGUMENTS, arguments);
        return span;
    }

    /** 创建通用内部子 span。 */
    public Span startChildSpan(String spanName) {
        String name = spanName != null ? spanName : "trace.child";
        return tracer.spanBuilder(name)
                .setParent(rootContext)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
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
        if (key == null || value == null) return;
        rootSpan.setAttribute(key, value);
    }

    public void setAttribute(String key, long value) {
        if (key == null) return;
        rootSpan.setAttribute(key, value);
    }

    public void setAttribute(String key, boolean value) {
        if (key == null) return;
        rootSpan.setAttribute(key, value);
    }

    /** 关闭 session（释放 context 绑定 + 结束 span） */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            rootSpan.end();
        } catch (Exception e) {
            Log.w(TAG, "Failed to end trace span", e);
        }
    }

    // ── 读取器 ──

    public String traceId() { return traceId; }

    public Tracer tracer() { return tracer; }

    public TraceAttributeWriter writer() { return writer; }

    public Scope makeCurrent() { return rootContext.makeCurrent(); }

    public TraceContext toTraceContext() {
        return new TraceContext(traceId, rootSpan.getSpanContext().getSpanId(), this);
    }
}
