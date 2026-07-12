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
import com.hirain.aiagent.memory.ContextMemoryGateway;
import com.hirain.aiagent.memory.MemorySnapshot;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Session 短期记忆上下文 Provider — 按 ITERATION_DYNAMIC 周期读取当前 session 的 ChatMessage 快照。
 * <p>
 * 设计原因：每次 AgentLoop 迭代重新读取，确保工具执行后的消息（AiMessage/ToolResult）可见。
 * persistent TEXT 中为 required，否则 session 消息为空。
 */
public class SessionMemoryContextProvider implements ContextProvider {

    private static final int DEFAULT_MAX_MESSAGES = 50;

    @Override
    public String name() {
        return "SessionMemoryContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.ITERATION_DYNAMIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return true;
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
            MemorySnapshot snapshot = memory.sessionMemorySnapshot(sessionId, DEFAULT_MAX_MESSAGES);
            messages = snapshot.messages();
        } catch (Exception e) {
            return ContextProviderResult.failure(name(),
                    "session_memory_read_failed: " + e.getMessage(),
                    ContextErrorCode.REQUIRED_PROVIDER_FAILED);
        }

        MessageContextContribution contribution = new MessageContextContribution(
                "session_memory", ContextVisibility.MODEL_VISIBLE, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.HIGH, ContextLifecycle.ITERATION_DYNAMIC,
                true, name(), MessageContextContribution.SOURCE_SESSION_MEMORY,
                messages, Map.of("session_id", sessionId));
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
