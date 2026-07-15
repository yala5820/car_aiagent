package com.hirain.aiagent.trace;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

public class TraceMessageFormatter {

    public TraceMessageFormatter() {
    }

    /** @deprecated 正文长度统一由 CaptureMode 决定，Formatter 不再截断。 */
    @Deprecated
    public TraceMessageFormatter(int maxLength) {
    }

    public String formatMessages(List<? extends ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (ChatMessage message : messages) {
            parts.add(formatMessage(message));
        }
        return String.join("\n", parts);
    }

    public String formatToolSpecifications(List<ToolSpecification> specs) {
        if (specs == null || specs.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            if (spec != null) names.add(spec.name());
        }
        return formatToolNames(names);
    }

    public String formatToolNames(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        return String.join(", ", names);
    }

    private String formatMessage(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return "system:" + systemMessage.text();
        }
        if (message instanceof UserMessage userMessage) {
            return "user:" + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                List<String> names = new ArrayList<>();
                aiMessage.toolExecutionRequests().forEach(req -> names.add(req.name()));
                return "assistant_tool_calls:" + String.join(", ", names);
            }
            return "assistant:" + (aiMessage.text() != null ? aiMessage.text() : "");
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return "tool:" + toolResult.toolName() + ":" + toolResult.text();
        }
        return message.type() + ":" + message.toString();
    }

}
