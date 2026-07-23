package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.MessageContextContribution;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * Session 短期记忆上下文 Provider — 按 ITERATION_DYNAMIC 周期读取当前 session 的 ChatMessage 快照。
 * <p>
 * 设计原因：每次 AgentLoop 迭代重新读取，确保工具执行后的消息（AiMessage/ToolResult）可见。
 * persistent TEXT 中为 required，否则 session 消息为空。
 */
public class SessionMemoryContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "SessionMemoryContextProvider";
    }

    @Override public String sourceKey() { return com.hirain.aiagent.context.ContextPolicies.SESSION_MEMORY; }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.ITERATION_DYNAMIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input).required();
    }

    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        ContextMemoryGateway memory = input.memoryGateway();
        String sessionId = session != null ? session.sessionId() : null;

        // required=true，不允许 gateway/sessionId 缺失或读取异常 fallback
        if (memory == null) {
            return ContextProviderResult.failure(name(),
                    "memoryGateway is null", ContextErrorCode.REQUIRED_PROVIDER_FAILED);
        }
        if (sessionId == null) {
            return ContextProviderResult.failure(name(),
                    "sessionId is null", ContextErrorCode.REQUIRED_PROVIDER_FAILED);
        }

        List<ChatMessage> messages;
        try {
            MemorySnapshot snapshot = memory.sessionMemorySnapshot(sessionId);
            messages = overlayCurrentKnowledgeResults(snapshot.messages(), session);
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("session_id", sessionId);
            metadata.put("history_repaired", snapshot.repaired());
            metadata.put("removed_message_count", snapshot.removedMessageCount());
            if (snapshot.repairReason() != null) {
                metadata.put("repair_reason", snapshot.repairReason());
            }
            List<com.hirain.aiagent.context.ContextContribution> contributions = new java.util.ArrayList<>();
            if (snapshot.summary() != null && !snapshot.summary().isEmpty()) {
                contributions.add(new TextContextContribution(
                        com.hirain.aiagent.context.ContextPolicies.resolve(
                                com.hirain.aiagent.context.ContextPolicies.SESSION_MEMORY_SUMMARY,
                                session, input), name(),
                        TextContextContribution.TARGET_CONTEXT_DATA, snapshot.summary(),
                        Map.of("summary_origin", "MemoryCompressor")));
            }
            MessageContextContribution contribution = new MessageContextContribution(
                    com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input),
                    name(), MessageContextContribution.SOURCE_SESSION_MEMORY,
                    messages, metadata);
            contributions.add(contribution);
            return ContextProviderResult.success(name(), contributions);
        } catch (Exception e) {
            return ContextProviderResult.failure(name(),
                    "session_memory_read_failed: " + e.getMessage(),
                    ContextErrorCode.REQUIRED_PROVIDER_FAILED);
        }

    }

    /** 当前请求的下一迭代需要完整 Evidence；旧请求没有 Buffer，因此只能保留其紧凑历史投影。 */
    private static List<ChatMessage> overlayCurrentKnowledgeResults(List<ChatMessage> stored, RequestSession session) {
        if (stored == null || session == null) return stored == null ? List.of() : stored;
        List<ChatMessage> output = new java.util.ArrayList<>(stored.size());
        for (ChatMessage message : stored) {
            if (message instanceof ToolExecutionResultMessage result
                    && "searchVehicleKnowledge".equals(result.toolName())) {
                String complete = session.knowledgeRequestState().turnBuffer().complete(result.id());
                output.add(complete != null ? new ToolExecutionResultMessage(result.id(), result.toolName(), complete) : result);
            } else output.add(message);
        }
        return List.copyOf(output);
    }
}
