package com.hirain.aiagent.context;

import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceContext;
import com.hirain.aiagent.trace.TraceSpanNames;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
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

    // ── Provider Span ──

    /**
     * 创建 context.provider.&lt;ProviderName&gt; 子 span。
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
        span.setAttribute("provider.produced_model_visible", hasModelVisible);
        span.end();
    }

    /** 判断 contribution 是否真正载有有效模型内容（空文本视为未入模）。 */
    private static boolean isEffectivelyEmpty(ContextContribution c) {
        if (c instanceof TextContextContribution tc) {
            return tc.content() == null || tc.content().isEmpty();
        }
        if (c instanceof MessageContextContribution mc) {
            return mc.messages() == null || mc.messages().isEmpty();
        }
        if (c instanceof ToolContextContribution tc) {
            return tc.toolSpecifications() == null || tc.toolSpecifications().isEmpty();
        }
        return false;
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
        if (c.messages() != null && !c.messages().isEmpty()) {
            // 记录角色分布作为辅助字段
            java.util.LinkedHashSet<String> roles = new java.util.LinkedHashSet<>();
            StringBuilder fullContent = new StringBuilder();
            int idx = 0;
            for (dev.langchain4j.data.message.ChatMessage m : c.messages()) {
                if (m == null) continue;
                roles.add(m.type().name());
                // 按 [index][role] content\n 格式拼接完整正文
                fullContent.append("[").append(idx).append("][")
                        .append(m.type().name()).append("] ")
                        .append(m).append("\n");
                idx++;
            }
            span.setAttribute("message.roles", String.join(", ", roles));
            // 完整正文（走 putText 保持脱敏/截断策略一致）
            traceContext.session().writer().putText(span, "message.content", fullContent.toString());
        }
        span.end();
    }

    /**
     * 记录 ToolContextContribution 的工具集 span。包含工具名、描述和参数 schema。
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
            StringBuilder schemaSb = new StringBuilder();
            for (ToolSpecification ts : c.toolSpecifications()) {
                if (ts == null) continue;
                names.add(ts.name());
                // 补齐 name / description / parameters 三部分，可还原完整 schema
                schemaSb.append("name=").append(ts.name()).append("\n");
                if (ts.description() != null && !ts.description().isEmpty()) {
                    schemaSb.append("description=").append(ts.description()).append("\n");
                }
                if (ts.parameters() != null) {
                    schemaSb.append("parameters=").append(ts.parameters().toString()).append("\n");
                }
                schemaSb.append("---\n");
            }
            span.setAttribute(TraceAttributeKeys.TOOLSET_TOOL_NAMES,
                    String.join(", ", names));
            traceContext.session().writer().putText(span, "toolset.schema", schemaSb.toString());
        }
        span.end();
    }
}
