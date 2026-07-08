package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;

/**
 * 将 Context 构建指标写入当前 TraceSession root span attribute。
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

    public void record(boolean enabled, ContextMode mode,
                       int providerCount, String providers,
                       int selectedToolCount, String selectedToolNames,
                       int sectionCount, int tokenEstimate,
                       boolean fallbackUsed, long buildMs, String error) {
        if (traceContext == null || traceContext.session() == null) return;

        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_ENABLED, enabled);
        traceContext.session().setAttribute(TraceAttributeKeys.CONTEXT_MODE, mode.name());
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
}
