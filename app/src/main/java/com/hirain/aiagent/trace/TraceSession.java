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

    // ── 旧方法（向后兼容，以 rootContext 为父） ──

    /** 创建 LLM 调用子 span（遵循 OpenInference 语义），以 rootContext 为父。 */
    public Span startLlmSpan(String modelName, int inputMessageCount) {
        return startLlmSpan(modelName, inputMessageCount, rootContext);
    }

    /** 创建工具执行子 span，以 rootContext 为父。 */
    public Span startToolSpan(String toolName, String arguments) {
        return startToolSpan(toolName, arguments, rootContext);
    }

    /** 创建通用内部子 span，以 rootContext 为父。 */
    public Span startChildSpan(String spanName) {
        return startChildSpan(spanName, rootContext);
    }

    // ── Parent-aware 新方法（Phase 1 新增，不改变旧调用行为） ──

    /** 创建 LLM 调用子 span（遵循 OpenInference 语义），使用显式 parent Context。 */
    public Span startLlmSpan(String modelName, int inputMessageCount, Context parent) {
        Context safeParent = parent != null ? parent : rootContext;
        Span span = tracer.spanBuilder(TraceSpanNames.GEN_AI_CHAT)
                .setParent(safeParent)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
        writer.putString(span, TraceAttributeKeys.GEN_AI_MODEL, modelName);
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT, inputMessageCount);
        return span;
    }

    /** 创建工具执行子 span，使用显式 parent Context。 */
    public Span startToolSpan(String toolName, String arguments, Context parent) {
        Context safeParent = parent != null ? parent : rootContext;
        Span span = tracer.spanBuilder(TraceSpanNames.TOOL_EXECUTE)
                .setParent(safeParent)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
        writer.putString(span, TraceAttributeKeys.TOOL_NAME, toolName);
        writer.putArgument(span, TraceAttributeKeys.TOOL_ARGUMENTS, arguments);
        return span;
    }

    /** 创建通用内部子 span，使用显式 parent Context。父 Context 为 null 时默认 rootContext。 */
    public Span startChildSpan(String spanName, Context parent) {
        Context safeParent = parent != null ? parent : rootContext;
        String name = spanName != null ? spanName : "trace.child";
        return tracer.spanBuilder(name)
                .setParent(safeParent)
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .startSpan();
    }

    /**
     * 创建 agent.loop span（以当前 TraceSession 的 root span 为父）。
     * 用于 AgentRuntime.execute() 替代 GlobalOpenTelemetry 直接使用。
     */
    public Span startAgentLoopSpan() {
        return tracer.spanBuilder(TraceSpanNames.AGENT_LOOP)
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
