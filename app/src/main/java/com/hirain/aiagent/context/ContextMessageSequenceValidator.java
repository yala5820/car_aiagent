package com.hirain.aiagent.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 消息序列校验器 — 校验消息顺序、唯一性和 Tool Calling 原子关系。
 * <p>
 * 使用 requestId -> PendingToolCall 的 pending map，保证 ToolExchange 完整闭合。
 * 作为 {@link ContextMessageAssembler} 的必经后置校验。
 */
public final class ContextMessageSequenceValidator {

    private ContextMessageSequenceValidator() {}

    /** 正在等待 ToolResult 的 Tool Call 记录。 */
    private static final class PendingToolCall {
        final String requestId;
        final String toolName;
        final int aiMessageIndex;

        PendingToolCall(String requestId, String toolName, int aiMessageIndex) {
            this.requestId = requestId;
            this.toolName = toolName;
            this.aiMessageIndex = aiMessageIndex;
        }
    }

    /**
     * 校验消息序列的合法性。
     *
     * @param messages 待校验的消息列表
     * @throws InvalidMessageSequenceException 如果序列非法
     */
    public static void validate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new InvalidMessageSequenceException(
                    "Messages must not be null or empty");
        }

        // 1. 校验索引 0 是唯一非空 SystemMessage
        ChatMessage first = messages.get(0);
        if (!(first instanceof SystemMessage)) {
            throw new InvalidMessageSequenceException(
                    "First message must be a SystemMessage, found: "
                            + (first != null ? first.getClass().getSimpleName() : "null"));
        }
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                throw new InvalidMessageSequenceException(
                        "Duplicate SystemMessage found at index " + i);
            }
        }

        // 2. 使用 pending map 校验 ToolExchange
        LinkedHashMap<String, PendingToolCall> pending = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);

            if (msg instanceof SystemMessage) {
                // system 已经检查过，且只能是第一条
            } else if (msg instanceof UserMessage) {
                // UserMessage 出现时 pending 必须为空
                if (!pending.isEmpty()) {
                    throw new InvalidMessageSequenceException(
                            "New UserMessage at index " + i
                                    + " found while " + pending.size()
                                    + " tool call(s) still pending: " + pending.keySet());
                }
            } else if (msg instanceof AiMessage) {
                AiMessage ai = (AiMessage) msg;
                if (ai.hasToolExecutionRequests()) {
                    // 新的 ToolCall 出现时 pending 必须为空
                    if (!pending.isEmpty()) {
                        throw new InvalidMessageSequenceException(
                                "New ToolCall AiMessage at index " + i
                                        + " found while " + pending.size()
                                        + " tool call(s) still pending: " + pending.keySet());
                    }
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        String id = req.id();
                        if (id == null || id.trim().isEmpty()) {
                            throw new InvalidMessageSequenceException(
                                    "ToolExecutionRequest with null/empty id at index " + i);
                        }
                        if (pending.containsKey(id)) {
                            throw new InvalidMessageSequenceException(
                                    "Duplicate tool request id '" + id + "' at index " + i);
                        }
                        pending.put(id, new PendingToolCall(id, req.name(), i));
                    }
                } else {
                    // 普通 AiMessage（无工具调用）出现时 pending 必须为空
                    if (!pending.isEmpty()) {
                        throw new InvalidMessageSequenceException(
                                "Plain AiMessage at index " + i
                                        + " found while " + pending.size()
                                        + " tool call(s) still pending: " + pending.keySet());
                    }
                }
            } else if (msg instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage result = (ToolExecutionResultMessage) msg;
                String id = result.id();
                String toolName = result.toolName();

                if (id == null || !pending.containsKey(id)) {
                    throw new InvalidMessageSequenceException(
                            "ToolExecutionResultMessage with unknown/absent id '"
                                    + id + "' at index " + i
                                    + ", expected one of: " + pending.keySet());
                }
                PendingToolCall expected = pending.get(id);
                if (toolName == null || !toolName.equals(expected.toolName)) {
                    throw new InvalidMessageSequenceException(
                            "ToolExecutionResultMessage tool name mismatch at index " + i
                                    + ": expected '" + expected.toolName
                                    + "' but got '" + toolName + "'");
                }
                pending.remove(id);
            }
        }

        // 3. 序列结束时 pending 必须为空
        if (!pending.isEmpty()) {
            throw new InvalidMessageSequenceException(
                    "Sequence ended with " + pending.size()
                            + " unclosed tool call(s): " + pending.keySet());
        }
    }

    /**
     * 非法消息序列异常。
     */
    public static class InvalidMessageSequenceException extends RuntimeException {
        public InvalidMessageSequenceException(String message) {
            super(message);
        }
    }
}
