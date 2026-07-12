package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * 将 Context 构建指标写入 TraceSession span（root 或 context.prepare/context.assemble）。
 * <p>
 * 设计原因：将 ContextOrchestrator 中的 trace 写入逻辑分离到此 class，
 * 避免 ContextOrchestrator 直接依赖 Trace 系统 API。
 * TraceContext 或 TraceSession 为空时 no-op。
 */
public class ContextTraceRecorder {

    private final TraceContext traceContext;

    public ContextTraceRecorder(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    /** 写入 root span attribute（旧路径兼容）。 */
    public void record(boolean enabled,
                       int providerCount, String providers,
                       int selectedToolCount, String selectedToolNames,
                       int sectionCount, int tokenEstimate,
                       boolean fallbackUsed, long buildMs, String error) {
        if (traceContext == null || traceContext.session() == null) return;

        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_ENABLED, enabled);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_PROVIDER_COUNT, providerCount);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_PROVIDERS, providers);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_SELECTED_TOOL_COUNT, selectedToolCount);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_SELECTED_TOOL_NAMES, selectedToolNames);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_SECTION_COUNT, sectionCount);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_TOKEN_ESTIMATE, tokenEstimate);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_FALLBACK_USED, fallbackUsed);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_BUILD_MS, buildMs);
        if (error != null) {
            traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_ERROR, error);
        }
    }

    /**
     * 创建 context.prepare span（parent 通常为 agent.loop scope 的 Context）。
     * 返回 0 表示未创建（trace 不可用），否则为 span 句柄。
     */
    public Span startPrepareSpan(Context parent) {
        if (traceContext == null || traceContext.session() == null) return null;
        return traceContext.session().startChildSpan(
                TraceSpanNames.CONTEXT_PREPARE, parent);
    }

    /**
     * 创建 context.assemble span（parent 通常为 agent.loop scope 的 Context）。
     */
    public Span startAssembleSpan(int iteration, Context parent) {
        if (traceContext == null || traceContext.session() == null) return null;
        Span span = traceContext.session().startChildSpan(
                TraceSpanNames.CONTEXT_ASSEMBLE, parent);
        span.setAttribute("iteration", iteration);
        return span;
    }

    /** 向 context.prepare span 写入 prepare 指标。 */
    public void recordPrepareToSpan(Span span, int providerCount,
                                     int successCount, int fallbackCount,
                                     int failedCount, int contributionCount,
                                     long durationMs) {
        if (span == null) return;
        span.setAttribute("provider.count", providerCount);
        span.setAttribute("provider.success", successCount);
        span.setAttribute("provider.fallback", fallbackCount);
        span.setAttribute("provider.failed", failedCount);
        span.setAttribute("contribution.count", contributionCount);
        span.setAttribute("duration.ms", durationMs);
    }

    /** 向 context.assemble span 写入装配指标。 */
    public void recordAssembleToSpan(Span span, int iteration,
                                      int messageCount, int toolCount,
                                      boolean fallbackUsed,
                                      int estimatedTokens, int maxTokens,
                                      boolean withinBudget,
                                      boolean compressionRecommended) {
        if (span == null) return;
        span.setAttribute("iteration", iteration);
        span.setAttribute("message.count", messageCount);
        span.setAttribute("tool.count", toolCount);
        span.setAttribute("context.fallback", fallbackUsed);
        span.setAttribute("tokens.estimated", estimatedTokens);
        span.setAttribute("tokens.max", maxTokens);
        span.setAttribute("budget.within", withinBudget);
        span.setAttribute("compression.recommended", compressionRecommended);
    }
}
