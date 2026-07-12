package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSpanNames;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
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

    // ── Provider Event ──

    /**
     * 向 span 添加 Provider 输出的 event。
     * 每执行一个 Provider 调用一次，记录 Provider 名称、状态、生命周期、contribution 信息。
     */
    public void recordProviderEvent(Span span, ContextProviderResult result,
                                     String providerName, String lifecycle, boolean required) {
        if (span == null || result == null) return;
        AttributesBuilder attrs = Attributes.builder()
                .put("provider.name", providerName)
                .put("provider.lifecycle", lifecycle)
                .put("provider.required", required)
                .put("provider.status", result.status() != null ? result.status().name() : "UNKNOWN")
                .put("provider.contribution_count", result.contributions().size());
        if (result.errorCode() != null) {
            attrs.put("provider.error_code", result.errorCode().name());
        }
        if (result.errorReason() != null) {
            attrs.put("provider.error_reason", result.errorReason());
        }
        span.addEvent("context.provider.output", attrs.build());
    }

    // ── 装配消息记录 ──

    /**
     * 将最终装配消息和工具规格写入 span attribute。
     * 格式为 [index][role] content\n，工具为 name= desc= params=\n。
     */
    public void recordAssembledMessages(Span span, List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecs) {
        if (span == null) return;
        if (messages != null && !messages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                sb.append("[").append(i).append("][").append(m.type()).append("] ")
                  .append(m).append("\n");
            }
            span.setAttribute("context.assembled_messages", sb.toString());
        }
        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ToolSpecification ts : toolSpecs) {
                sb.append("name=").append(ts.name())
                  .append(" desc=").append(ts.description() != null ? ts.description() : "")
                  .append(" params=")
                  .append(ts.parameters() != null ? ts.parameters().toString() : "{}")
                  .append("\n");
            }
            span.setAttribute("context.assembled_tool_specs", sb.toString());
        }
    }

    /**
     * 为每条最终消息添加 context.message event。
     * 每条消息含 index、role、content 属性。
     */
    public void recordAssembleMessagesAsEvents(Span span, List<ChatMessage> messages) {
        if (span == null || messages == null) return;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            AttributesBuilder attrs = Attributes.builder()
                    .put("message.index", i)
                    .put("message.role", m.type().toString())
                    .put("message.content", m.toString());
            span.addEvent("context.message", attrs.build());
        }
    }
}
