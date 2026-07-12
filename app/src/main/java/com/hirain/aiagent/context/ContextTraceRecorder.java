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

    // ── Provider Span ──

    /**
     * 创建 context.provider.&lt;ProviderName&gt; 子 span，替换旧的 recordProviderEvent() event 机制。
     * parent 通常为 context.prepare 或 context.assemble span 的 Context。
     */
    public Span startProviderSpan(String providerName, Context parent) {
        if (traceContext == null || traceContext.session() == null) return null;
        String spanName = "context.provider." + providerName;
        return traceContext.session().startChildSpan(spanName, parent);
    }

    /**
     * 结束 provider span 并写入执行指标（不含 contribution 正文内容）。
     */
    public void finishProviderSpan(Span span, ContextProviderResult result,
                                    String lifecycle, boolean required, long durationMs) {
        if (span == null) return;
        span.setAttribute("provider.name", result.providerName());
        span.setAttribute("provider.lifecycle", lifecycle);
        span.setAttribute("provider.required", required);
        span.setAttribute("provider.status", result.status() != null ? result.status().name() : "UNKNOWN");
        span.setAttribute("provider.contribution_count", result.contributions().size());
        span.setAttribute("provider.duration_ms", durationMs);
        if (result.errorCode() != null) {
            span.setAttribute("provider.error_code", result.errorCode().name());
        }
        if (result.errorReason() != null) {
            span.setAttribute("provider.error_reason", result.errorReason());
        }
        // 判断至少有一个 MODEL_VISIBLE 贡献且非空（避免空 content 被误记为入模）
        boolean hasModelVisible = result.contributions().stream()
                .anyMatch(c -> c.visibility() == ContextVisibility.MODEL_VISIBLE
                        && !isEffectivelyEmpty(c));
        span.setAttribute("provider.included_in_model", hasModelVisible);
        span.end();
    }

    /** 判断 contribution 是否真正载有有效模型内容（空文本视为未入模）。 */
    private static boolean isEffectivelyEmpty(ContextContribution c) {
        if (c instanceof TextContextContribution tc) {
            return tc.content() == null || tc.content().isEmpty();
        }
        // MessageContribution 和 ToolContribution 只要存在就视为有内容
        return false;
    }

    // ── 装配消息记录 ──

    /**
     * 将最终装配消息和工具规格写入 span attribute（兼容旧字段）。
     * 格式为 [index][role] content\n，工具为 name= desc= params=\n。
     * @deprecated 主排障入口已迁移至 context.fragment / context.message span，
     *             见 {@link #recordFragment(TextContextContribution)} 和 {@link #recordMessage(MessageContextContribution)}。
     */
    @Deprecated
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
     * 为每条最终消息添加 context.message event（兼容旧字段）。
     * 每条消息含 index、role、content 属性。
     * @deprecated 主排障入口已迁移至 context.message span，
     *             见 {@link #recordMessage(MessageContextContribution)}。
     */
    @Deprecated
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

    // ── Fragments / Message / Toolset 记录 ──

    /**
     * 记录 TextContextContribution 的模型输入片段 span。
     * span 名称 = context.fragment.&lt;sourceKey&gt;，parent = Context.current()。
     * 调用时需确保在 context.assemble span scope 内。
     */
    public void recordFragment(TextContextContribution c) {
        if (traceContext == null || traceContext.session() == null) return;
        if (c == null) return;
        String spanName = TraceSpanNames.CONTEXT_FRAGMENT + "." + c.sourceKey();
        Span span = traceContext.session().startChildSpan(spanName,
                io.opentelemetry.context.Context.current());
        span.setAttribute(TraceAttributeKeys.FRAGMENT_SOURCE_KEY, c.sourceKey());
        span.setAttribute(TraceAttributeKeys.FRAGMENT_TARGET_AREA, c.targetArea());
        span.setAttribute(TraceAttributeKeys.FRAGMENT_PROVIDER, c.providerName());
        boolean actuallyIncluded = c.visibility() == ContextVisibility.MODEL_VISIBLE
                && c.content() != null && !c.content().isEmpty();
        span.setAttribute(TraceAttributeKeys.FRAGMENT_INCLUDED_IN_MODEL, actuallyIncluded);
        traceContext.session().writer().putText(span,
                TraceAttributeKeys.FRAGMENT_CONTENT, c.content());
        span.end();
    }

    /**
     * 记录 MessageContextContribution 的模型输入消息 span。
     * span 名称 = context.message.&lt;messageSource&gt;，记录消息数量和来源。
     */
    public void recordMessage(MessageContextContribution c) {
        if (traceContext == null || traceContext.session() == null) return;
        if (c == null) return;
        String spanName = TraceSpanNames.CONTEXT_MESSAGE + "." + c.messageSource().toLowerCase(java.util.Locale.ROOT);
        Span span = traceContext.session().startChildSpan(spanName,
                io.opentelemetry.context.Context.current());
        span.setAttribute(TraceAttributeKeys.MESSAGE_SOURCE, c.messageSource());
        span.setAttribute(TraceAttributeKeys.MESSAGE_PROVIDER, c.providerName());
        int msgCount = c.messages() != null ? c.messages().size() : 0;
        span.setAttribute(TraceAttributeKeys.MESSAGE_COUNT, msgCount);
        // 记录消息角色分布（如 "USER,ASSISTANT,TOOL"），不逐条写入完整内容（避免 double-dump）
        if (c.messages() != null && !c.messages().isEmpty()) {
            java.util.LinkedHashSet<String> roles = new java.util.LinkedHashSet<>();
            for (dev.langchain4j.data.message.ChatMessage m : c.messages()) {
                if (m != null) roles.add(m.type().name());
            }
            span.setAttribute("message.roles", String.join(", ", roles));
            // 写入摘要帮助排障：前 200 字符 + 截断标记
            String firstMsg = c.messages().get(0).toString();
            String summary = firstMsg.length() > 200
                    ? firstMsg.substring(0, 200) + "... (truncated)"
                    : firstMsg;
            traceContext.session().writer().putText(span, "message.content_summary", summary);
        }
        span.end();
    }

    /**
     * 记录 ToolContextContribution 的工具集 span。
     * span 名称 = context.toolset，记录工具数量与名称。
     */
    public void recordToolset(ToolContextContribution c) {
        if (traceContext == null || traceContext.session() == null) return;
        if (c == null) return;
        Span span = traceContext.session().startChildSpan(TraceSpanNames.CONTEXT_TOOLSET,
                io.opentelemetry.context.Context.current());
        span.setAttribute(TraceAttributeKeys.TOOLSET_PROVIDER, c.providerName());
        int count = c.toolSpecifications() != null ? c.toolSpecifications().size() : 0;
        span.setAttribute(TraceAttributeKeys.TOOLSET_TOOL_COUNT, count);
        if (c.toolSpecifications() != null) {
            List<String> names = new java.util.ArrayList<>();
            for (ToolSpecification ts : c.toolSpecifications()) {
                if (ts != null) names.add(ts.name());
            }
            span.setAttribute(TraceAttributeKeys.TOOLSET_TOOL_NAMES,
                    String.join(", ", names));
        }
        span.end();
    }
}
