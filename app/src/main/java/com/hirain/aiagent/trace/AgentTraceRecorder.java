package com.hirain.aiagent.trace;

import com.hirain.aiagent.core.SafetyVerdict;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

public class AgentTraceRecorder {

    private final TraceSession session;
    private final TraceAttributeWriter writer;
    private final TraceMessageFormatter formatter;

    public AgentTraceRecorder(TraceSession session) {
        this.session = session;
        this.writer = session != null ? session.writer() : null;
        this.formatter = new TraceMessageFormatter();
    }

    public Span startPromptAssembly(String persona,
                                    int iteration,
                                    List<? extends ChatMessage> transientMessages,
                                    List<? extends ChatMessage> chatMessages,
                                    int messageCount,
                                    List<?> toolSpecsOrNames) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.PROMPT_ASSEMBLY);
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
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.GEN_AI_CHAT);
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

    public Span startTool(ToolExecutionRequest request, int iteration) {
        if (session == null) return null;
        Span span = session.startChildSpan(TraceSpanNames.TOOL_EXECUTE);
        writer.putLong(span, TraceAttributeKeys.AGENT_ITERATION, iteration);
        if (request != null) {
            writer.putString(span, TraceAttributeKeys.TOOL_NAME, request.name());
            writer.putArgument(span, TraceAttributeKeys.TOOL_ARGUMENTS, request.arguments());
        }
        return span;
    }

    public void finishTool(Span span, String result, SafetyVerdict verdict) {
        if (span == null) return;
        boolean vetoed = verdict != null && verdict.isVetoed();
        writer.putBoolean(span, TraceAttributeKeys.TOOL_SUCCESS, !vetoed);
        writer.putBoolean(span, TraceAttributeKeys.TOOL_SAFETY_VETO, vetoed);
        if (vetoed) {
            writer.putString(span, TraceAttributeKeys.TOOL_SAFETY_VETO_REASON, verdict.reason());
        }
        writer.putResult(span, TraceAttributeKeys.TOOL_OUTPUT, result);
    }

    public Span startMemory(String operation, int inputChars) {
        if (session == null) return null;
        String normalized = operation != null ? operation : "";
        String spanName = "compress".equals(normalized)
                ? TraceSpanNames.MEMORY_COMPRESS
                : TraceSpanNames.MEMORY_EXTRACT;
        Span span = session.startChildSpan(spanName);
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
