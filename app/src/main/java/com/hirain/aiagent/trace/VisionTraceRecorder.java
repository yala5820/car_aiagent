package com.hirain.aiagent.trace;

import io.opentelemetry.api.trace.Span;

/** 前向视觉 Tool 的低敏子 Span 记录器；仅记录图片元数据与结果状态。 */
public final class VisionTraceRecorder {
    private final io.opentelemetry.api.trace.Tracer tracer;
    public VisionTraceRecorder(io.opentelemetry.api.trace.Tracer tracer) { this.tracer = tracer; }
    public Span startImageLoad() { return tracer.spanBuilder(TraceSpanNames.VISION_IMAGE_LOAD).startSpan(); }
    public Span startModel() { return tracer.spanBuilder(TraceSpanNames.VISION_MODEL).startSpan(); }
    public void finishImageLoad(Span span, String id, String source, String mime, long size, long loadedAtMs, String status) {
        if (span == null) return;
        span.setAttribute(TraceAttributeKeys.VISION_IMAGE_ID, id == null ? "" : id);
        span.setAttribute(TraceAttributeKeys.VISION_IMAGE_SOURCE, source == null ? "" : source);
        span.setAttribute(TraceAttributeKeys.VISION_IMAGE_MIME_TYPE, mime == null ? "" : mime);
        span.setAttribute(TraceAttributeKeys.VISION_IMAGE_SIZE_BYTES, size);
        span.setAttribute(TraceAttributeKeys.VISION_IMAGE_LOADED_AT_MS, loadedAtMs);
        span.setAttribute(TraceAttributeKeys.VISION_STATUS, status); span.end();
    }
    public void finishModel(Span span, String status, long durationMs) { if(span!=null){span.setAttribute(TraceAttributeKeys.VISION_STATUS,status);span.setAttribute(TraceAttributeKeys.VISION_DURATION_MS,durationMs);span.end();} }
    public void recordResult(String status) {
        Span span = tracer.spanBuilder(TraceSpanNames.VISION_RESULT).startSpan();
        span.setAttribute(TraceAttributeKeys.VISION_STATUS, status == null ? "INTERNAL_ERROR" : status);
        span.end();
    }
}
