package com.hirain.aiagent.trace;

import com.hirain.aiagent.safety.SafetyDecision;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 记录 Agent 循环中各类操作的 span 属性。
 * 每次请求创建一个新实例，使用 TraceSession 创建子 span。
 */
public class AgentTraceRecorder {

    private final TraceSession session;
    private final TraceAttributeWriter writer;
    private final TraceMessageFormatter formatter;
    // memory span 专用 parent context（通常为 agent.loop 的 Context，在 orchestrator 中设置）
    private Context memoryParentContext;

    public AgentTraceRecorder(TraceSession session) {
        this.session = session;
        this.writer = session != null ? session.writer() : null;
        this.formatter = new TraceMessageFormatter();
    }

    /**
     * 设置 memory span 的父 Context（在进入迭代循环前调用一次）。
     * 确保 memory 操作 span 挂在 agent.loop 下而非 iteration 下。
     */
    public void setMemoryParentContext(Context parent) {
        this.memoryParentContext = parent;
    }

    /** 创建 agent.iteration span，作为本轮迭代的容器。 */
    public Span startIteration(int number, Context parent) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.AGENT_ITERATION, parent);
        span.setAttribute(TraceAttributeKeys.AGENT_ITERATION, number);
        return span;
    }

    public Span startPromptAssembly(String persona,
                                    int iteration,
                                    List<? extends ChatMessage> transientMessages,
                                    List<? extends ChatMessage> chatMessages,
                                    int messageCount,
                                    List<?> toolSpecsOrNames) {
        return startPromptAssembly(persona, iteration, transientMessages, chatMessages,
                messageCount, toolSpecsOrNames, null);
    }

    /**
     * Parent-aware 重载，使用显式 parent Context 创建 PROMPT_ASSEMBLY span。
     * parent 为 null 时与旧方法行为一致（root parent）。
     */
    public Span startPromptAssembly(String persona,
                                    int iteration,
                                    List<? extends ChatMessage> transientMessages,
                                    List<? extends ChatMessage> chatMessages,
                                    int messageCount,
                                    List<?> toolSpecsOrNames,
                                    Context parentContext) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.PROMPT_ASSEMBLY, parentContext);
        writer.putString(span, TraceAttributeKeys.AGENT_PERSONA, persona);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        writer.putText(span, TraceAttributeKeys.PROMPT_TRANSIENT_MESSAGES,
                formatter.formatMessages(transientMessages));
        writer.putText(span, TraceAttributeKeys.PROMPT_CHAT_MESSAGES,
                formatter.formatMessages(chatMessages));
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT, messageCount);
        writer.putLong(span, TraceAttributeKeys.PROMPT_TOOL_SPEC_COUNT,
                toolSpecsOrNames != null ? toolSpecsOrNames.size() : 0);
        writer.putText(span, TraceAttributeKeys.PROMPT_TOOL_SPECS,
                formatToolObjects(toolSpecsOrNames));
        return span;
    }

    public Span startLlmCall(String modelName, int iteration, int messageCount) {
        return startLlmCall(modelName, iteration, messageCount, null);
    }

    /**
     * Parent-aware 重载，使用显式 parent Context 创建 GEN_AI_CHAT span。
     */
    public Span startLlmCall(String modelName, int iteration, int messageCount,
                              Context parentContext) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.GEN_AI_CHAT, parentContext);
        writer.putString(span, TraceAttributeKeys.GEN_AI_PROVIDER, "dashscope");
        writer.putString(span, TraceAttributeKeys.GEN_AI_MODEL, modelName);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        writer.putLong(span, TraceAttributeKeys.PROMPT_MESSAGE_COUNT, messageCount);
        return span;
    }

    public void enrichLlmResponse(Span span, ChatResponse response) {
        if (span == null || response == null) return;
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage != null) {
            writer.putResult(span, TraceAttributeKeys.GEN_AI_OUTPUT, aiMessage.text());
            if (aiMessage.hasToolExecutionRequests()) {
                List<String> toolNames = new ArrayList<>();
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    toolNames.add(req.name());
                }
                writer.putString(span, TraceAttributeKeys.GEN_AI_TOOL_CALLS,
                        String.join(", ", toolNames));
            }
        }
        try {
            dev.langchain4j.model.output.TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                if (usage.inputTokenCount() != null) {
                    writer.putLong(span, TraceAttributeKeys.GEN_AI_INPUT_TOKENS,
                            usage.inputTokenCount());
                }
                if (usage.outputTokenCount() != null) {
                    writer.putLong(span, TraceAttributeKeys.GEN_AI_OUTPUT_TOKENS,
                            usage.outputTokenCount());
                }
                if (usage.totalTokenCount() != null) {
                    writer.putLong(span, TraceAttributeKeys.GEN_AI_TOTAL_TOKENS,
                            usage.totalTokenCount());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 记录完整 LLM 请求内容到 gen_ai.chat span。所有正文/参数类字段走 writer 保持策略一致。 */
    public void recordLlmRequest(Span span, String modelName, int iteration,
                                  List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecs) {
        if (span == null) return;
        writer.putString(span, "gen_ai.request.model", modelName != null ? modelName : "");
        writer.putLong(span, "gen_ai.request.iteration", iteration);
        if (messages != null) {
            writer.putLong(span, "gen_ai.request.message_count", messages.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                sb.append("[").append(i).append("][").append(m.type()).append("] ")
                  .append(m).append("\n");
            }
            writer.putText(span, "gen_ai.request.messages", sb.toString());
        }
        if (toolSpecs != null) {
            writer.putLong(span, "gen_ai.request.tool_count", toolSpecs.size());
            StringBuilder sb = new StringBuilder();
            for (ToolSpecification ts : toolSpecs) {
                sb.append("name=").append(ts.name()).append("\n");
                if (ts.description() != null && !ts.description().isEmpty()) {
                    sb.append("description=").append(ts.description()).append("\n");
                }
                if (ts.parameters() != null) {
                    sb.append("parameters=").append(ts.parameters().toString()).append("\n");
                }
                sb.append("---\n");
            }
            writer.putText(span, "gen_ai.request.tool_specs", sb.toString());
        }
    }

    public Span startTool(ToolExecutionRequest request, int iteration) {
        return startTool(request, iteration, null);
    }

    /** Parent-aware 重载。 */
    public Span startTool(ToolExecutionRequest request, int iteration,
                           Context parentContext) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.TOOL_EXECUTE, parentContext);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        if (request != null) {
            writer.putString(span, TraceAttributeKeys.TOOL_NAME, request.name());
            writer.putArgument(span, TraceAttributeKeys.TOOL_ARGUMENTS, request.arguments());
        }
        return span;
    }

    public void finishTool(Span span, String result, SafetyDecision decision) {
        // 无细粒度执行诊断的兼容路径：安全放行且未抛异常时视为执行成功。
        finishTool(span, result, decision,
                decision != null && decision.isAllowed());
    }

    /**
     * 结束工具 span，并同时考虑安全决定和真实执行结果。
     * DENY 是预期业务结果，不标记为系统异常；安全已放行但 dispatch 失败则必须标记 ERROR。
     */
    public void finishTool(Span span, String result, SafetyDecision decision,
                           boolean executionSucceeded) {
        if (span == null) return;
        boolean safetyAllowed = decision != null && decision.isAllowed();
        boolean success = safetyAllowed && executionSucceeded;
        writer.putBoolean(span, TraceAttributeKeys.TOOL_SUCCESS, success);
        if (decision != null) {
            writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_DECISION,
                    decision.type().name());
            writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_REASON_CODE,
                    decision.reasonCode().name());
            if (!decision.isAllowed()) {
                writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_REASON,
                        decision.reason());
            }
        }
        writer.putResult(span, TraceAttributeKeys.TOOL_OUTPUT, result);
        if (decision == null || (safetyAllowed && !executionSucceeded)) {
            span.setStatus(StatusCode.ERROR, "tool_failed");
        }
    }

    public Span startMemory(String operation, int inputChars) {
        Context parent = memoryParentContext != null
                ? memoryParentContext
                : io.opentelemetry.context.Context.current();
        return startMemory(operation, inputChars, parent);
    }

    /** Parent-aware 重载。 */
    public Span startMemory(String operation, int inputChars,
                             Context parentContext) {
        if (session == null) return null;
        String normalized = operation != null ? operation : "";
        String spanName = "compress".equals(normalized)
                ? TraceSpanNames.MEMORY_COMPRESS
                : TraceSpanNames.MEMORY_EXTRACT;
        Span span = session.startChildSpan(spanName, parentContext);
        writer.putString(span, TraceAttributeKeys.MEMORY_OPERATION, normalized);
        writer.putLong(span, TraceAttributeKeys.MEMORY_INPUT_CHARS, Math.max(inputChars, 0));
        return span;
    }

    public void finishMemoryExtract(Span span, String prompt, String output, int candidateCount) {
        if (span == null) return;
        writer.putString(span, TraceAttributeKeys.MEMORY_OPERATION, "extract");
        writer.putText(span, TraceAttributeKeys.MEMORY_PROMPT, prompt);
        writer.putResult(span, TraceAttributeKeys.MEMORY_OUTPUT, output);
        writer.putLong(span, TraceAttributeKeys.MEMORY_CANDIDATE_COUNT, Math.max(candidateCount, 0));
        writer.putLong(span, TraceAttributeKeys.MEMORY_OUTPUT_CHARS,
                output != null ? output.length() : 0);
    }

    public void finishMemoryCompress(Span span, boolean compressed, String prompt, String summary) {
        if (span == null) return;
        writer.putString(span, TraceAttributeKeys.MEMORY_OPERATION, "compress");
        writer.putBoolean(span, TraceAttributeKeys.MEMORY_COMPRESSED, compressed);
        writer.putText(span, TraceAttributeKeys.MEMORY_PROMPT, prompt);
        writer.putResult(span, TraceAttributeKeys.MEMORY_OUTPUT, summary);
        writer.putLong(span, TraceAttributeKeys.MEMORY_OUTPUT_CHARS,
                summary != null ? summary.length() : 0);
    }

    public void recordException(Span span, Throwable throwable) {
        if (span == null || throwable == null) return;
        span.recordException(throwable);
        span.setStatus(StatusCode.ERROR, throwable.getMessage() != null
                ? throwable.getMessage()
                : throwable.getClass().getSimpleName());
        writer.putString(span, TraceAttributeKeys.ERROR_TYPE, throwable.getClass().getName());
        writer.putString(span, TraceAttributeKeys.ERROR_MESSAGE, throwable.getMessage());
    }

    // ── Tool 子阶段 span ──

    /** 创建 tool.safety_check 子 span（parent = Context.current() = tool.execute）。 */
    public Span startToolSafetyCheck() {
        if (session == null) return null;
        return session.startChildSpan(TraceSpanNames.TOOL_SAFETY_CHECK,
                io.opentelemetry.context.Context.current());
    }

    /** 结束 safety_check span，记录统一 Engine 返回的审核结果。 */
    public void finishToolSafetyCheck(Span span, SafetyDecision decision) {
        if (span == null) return;
        if (decision != null) {
            writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_DECISION,
                    decision.type().name());
            writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_REASON_CODE,
                    decision.reasonCode().name());
            if (!decision.isAllowed()) {
                writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_REASON,
                        decision.reason());
            }
        }
        span.end();
    }

    /** 创建 tool.dispatch 子 span。仅在安全通过后调用。 */
    public Span startToolDispatch(String toolName) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.TOOL_DISPATCH,
                io.opentelemetry.context.Context.current());
        span.setAttribute(TraceAttributeKeys.TOOL_NAME, toolName);
        return span;
    }

    /** 结束 dispatch span（基础版）。 */
    public void finishToolDispatch(Span span, boolean success,
                                    String dispatchTarget, long durationMs) {
        if (span == null) return;
        span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_SUCCESS, success);
        if (dispatchTarget != null) {
            span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_TARGET, dispatchTarget);
        }
        span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_DURATION_MS, durationMs);
        span.end();
    }

    /** 结束 dispatch span（增强版 — 含目标类/方法/阶段诊断）。 */
    public void finishToolDispatch(Span span, boolean success,
                                    String targetClass, String targetMethod,
                                    long durationMs,
                                    boolean argumentParseSuccess, boolean invokeSuccess) {
        if (span == null) return;
        span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_SUCCESS, success);
        if (targetClass != null) {
            span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_TARGET_CLASS, targetClass);
        }
        if (targetMethod != null) {
            span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_TARGET_METHOD, targetMethod);
        }
        span.setAttribute(TraceAttributeKeys.TOOL_DISPATCH_DURATION_MS, durationMs);
        span.setAttribute(TraceAttributeKeys.TOOL_ARGUMENT_PARSE_SUCCESS, argumentParseSuccess);
        span.setAttribute(TraceAttributeKeys.TOOL_INVOKE_SUCCESS, invokeSuccess);
        span.end();
    }

    /** 创建 tool.result_writeback 子 span。 */
    public Span startToolWriteback() {
        if (session == null) return null;
        return session.startChildSpan(TraceSpanNames.TOOL_RESULT_WRITEBACK,
                io.opentelemetry.context.Context.current());
    }

    /** 结束 writeback span。 */
    public void finishToolWriteback(Span span, boolean writtenToMemory) {
        if (span == null) return;
        span.setAttribute(TraceAttributeKeys.TOOL_WRITEBACK_TO_MEMORY, writtenToMemory);
        span.end();
    }

    private String formatToolObjects(List<?> toolSpecsOrNames) {
        if (toolSpecsOrNames == null || toolSpecsOrNames.isEmpty()) return "";
        Object first = toolSpecsOrNames.get(0);
        if (first instanceof ToolSpecification) {
            List<ToolSpecification> specs = new ArrayList<>();
            for (Object item : toolSpecsOrNames) {
                if (item instanceof ToolSpecification spec) specs.add(spec);
            }
            return formatter.formatToolSpecifications(specs);
        }
        List<String> names = new ArrayList<>();
        for (Object item : toolSpecsOrNames) {
            if (item != null) names.add(String.valueOf(item));
        }
        return formatter.formatToolNames(names);
    }
}
