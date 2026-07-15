package com.hirain.aiagent.safety.confirmation;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 等待文本确认的原始 Tool 动作快照。
 * <p>
 * toolName 与 arguments 只从首次模型请求复制，确认请求不能替换任何执行参数。
 */
public final class PendingToolAction {

    public enum State {
        PENDING,
        CONSUMED,
        CANCELLED,
        EXPIRED
    }

    private final String confirmationId;
    private final String sessionId;
    private final String originalRequestId;
    private final String toolRequestId;
    private final String toolName;
    private final String arguments;
    private final String actionSummary;
    private final long createdAtMs;
    private final long expiresAtMs;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public PendingToolAction(String confirmationId, String sessionId,
                             String originalRequestId, ToolExecutionRequest request,
                             String actionSummary, long createdAtMs, long expiresAtMs) {
        this.confirmationId = Objects.requireNonNull(confirmationId, "confirmationId");
        this.sessionId = sessionId;
        this.originalRequestId = Objects.requireNonNull(originalRequestId, "originalRequestId");
        Objects.requireNonNull(request, "request");
        this.toolRequestId = Objects.requireNonNull(request.id(), "toolRequestId");
        this.toolName = Objects.requireNonNull(request.name(), "toolName");
        JsonElement parsedArguments = JsonParser.parseString(
                Objects.requireNonNull(request.arguments(), "arguments"));
        if (!parsedArguments.isJsonObject()) {
            throw new IllegalArgumentException("PendingAction arguments must be a JSON object");
        }
        this.arguments = parsedArguments.getAsJsonObject().toString();
        this.actionSummary = Objects.requireNonNull(actionSummary, "actionSummary");
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
    }

    public ToolExecutionRequest toOriginalRequest() {
        return ToolExecutionRequest.builder()
                .id(toolRequestId)
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    boolean transition(State target) {
        return state.compareAndSet(State.PENDING, target);
    }

    public String confirmationId() { return confirmationId; }
    public String sessionId() { return sessionId; }
    public String originalRequestId() { return originalRequestId; }
    public String toolRequestId() { return toolRequestId; }
    public String toolName() { return toolName; }
    public String arguments() { return arguments; }
    public String actionSummary() { return actionSummary; }
    public long createdAtMs() { return createdAtMs; }
    public long expiresAtMs() { return expiresAtMs; }
    public State state() { return state.get(); }
}
