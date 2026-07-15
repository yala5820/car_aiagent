package com.hirain.aiagent.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 校验持久化 Session 历史中的 ConversationTurn 与 ToolExchange。
 * <p>
 * 仅允许修复进程中断产生的尾部未闭合 ToolExchange；中间损坏、未知结果、
 * 重复调用 ID 和工具名不一致都必须硬失败，避免 Context 静默吞掉历史问题。
 */
public final class SessionHistorySequenceValidator {

    private SessionHistorySequenceValidator() {}

    public static ValidationResult validate(List<ChatMessage> source) {
        List<ChatMessage> messages = source != null ? new ArrayList<>(source) : new ArrayList<>();
        if (messages.isEmpty()) return ValidationResult.valid(messages);

        Set<String> seenCallIds = new HashSet<>();
        Map<String, String> pending = new LinkedHashMap<>();
        int currentTurnStart = -1;
        boolean currentTurnHasContent = false;
        boolean terminalAiSeen = false;
        boolean summarySeen = false;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof UserMessage) {
                String userText = ((UserMessage) message).singleText();
                if (userText != null && userText.startsWith("【对话摘要】")) {
                    if (i != 0 || summarySeen) {
                        throw invalid("summary marker must appear at most once at history head");
                    }
                    summarySeen = true;
                    continue;
                }
                if (!pending.isEmpty()) {
                    throw invalid("unclosed tool exchange before user message at index " + i);
                }
                if (currentTurnStart >= 0 && !currentTurnHasContent) {
                    throw invalid("empty conversation turn before index " + i);
                }
                currentTurnStart = i;
                currentTurnHasContent = false;
                terminalAiSeen = false;
                continue;
            }
            if (currentTurnStart < 0) {
                throw invalid("history must start each turn with UserMessage, index=" + i);
            }
            if (message instanceof AiMessage) {
                AiMessage ai = (AiMessage) message;
                if (terminalAiSeen) {
                    throw invalid("message found after terminal AiMessage at index " + i);
                }
                if (!pending.isEmpty()) {
                    throw invalid("AiMessage found while tool calls are pending at index " + i);
                }
                currentTurnHasContent = true;
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                        String id = request.id();
                        if (id == null || id.trim().isEmpty()) {
                            throw invalid("tool call id is empty at index " + i);
                        }
                        if (!seenCallIds.add(id)) {
                            throw invalid("duplicate tool call id '" + id + "'");
                        }
                        pending.put(id, request.name());
                    }
                } else {
                    terminalAiSeen = true;
                }
                continue;
            }
            if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage result = (ToolExecutionResultMessage) message;
                String expectedName = pending.get(result.id());
                if (expectedName == null) {
                    throw invalid("unknown tool result id '" + result.id() + "' at index " + i);
                }
                if (!expectedName.equals(result.toolName())) {
                    throw invalid("tool name mismatch for id '" + result.id() + "'");
                }
                pending.remove(result.id());
                continue;
            }
            throw invalid("unsupported message type at index " + i + ": "
                    + message.getClass().getSimpleName());
        }

        if (!pending.isEmpty()) {
            if (currentTurnStart < 0) {
                throw invalid("unclosed tool exchange without conversation turn");
            }
            List<ChatMessage> repaired = new ArrayList<>(messages.subList(0, currentTurnStart));
            return ValidationResult.repaired(repaired, messages.size() - currentTurnStart,
                    "TAIL_UNCLOSED_TOOL_EXCHANGE");
        }
        if (currentTurnStart >= 0 && !currentTurnHasContent) {
            List<ChatMessage> repaired = new ArrayList<>(messages.subList(0, currentTurnStart));
            return ValidationResult.repaired(repaired, messages.size() - currentTurnStart,
                    "TAIL_USER_WITHOUT_RESPONSE");
        }
        return ValidationResult.valid(messages);
    }

    private static InvalidSessionHistoryException invalid(String detail) {
        return new InvalidSessionHistoryException("invalid_session_history: " + detail);
    }

    public static final class ValidationResult {
        private final List<ChatMessage> messages;
        private final boolean repaired;
        private final int removedMessageCount;
        private final String repairReason;

        private ValidationResult(List<ChatMessage> messages, boolean repaired,
                                 int removedMessageCount, String repairReason) {
            this.messages = List.copyOf(messages);
            this.repaired = repaired;
            this.removedMessageCount = removedMessageCount;
            this.repairReason = repairReason;
        }

        static ValidationResult valid(List<ChatMessage> messages) {
            return new ValidationResult(messages, false, 0, null);
        }

        static ValidationResult repaired(List<ChatMessage> messages, int removed, String reason) {
            return new ValidationResult(messages, true, removed, reason);
        }

        public List<ChatMessage> messages() { return messages; }
        public boolean repaired() { return repaired; }
        public int removedMessageCount() { return removedMessageCount; }
        public String repairReason() { return repairReason; }
    }

    public static final class InvalidSessionHistoryException extends RuntimeException {
        public InvalidSessionHistoryException(String message) {
            super(message);
        }
    }
}
