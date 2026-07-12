package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.memory.MemoryOrchestrator;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 记忆注入预处理器 — 在每轮 LLM 调用前注入长期记忆上下文。
 * <p>
 * 仅在首轮迭代（iteration = 0）时注入，避免重复。
 * <p>
 * 注意：Phase 4 起长期记忆唯一注入点已收敛到 AgentLoop 的 transient SystemMessage
 * （{@code AgentLoopOrchestrator.buildSystemPromptMessage()}）。
 * 此 preprocessor 仅保留给旧配置兼容，不能再把长期记忆拼入用户消息。
 * TEXT / VOICE 主路径已不再使用此 preprocessor。
 */
public class MemoryPreProcessor implements PreProcessor {

    private final MemoryOrchestrator memoryOrchestrator;

    public MemoryPreProcessor(MemoryOrchestrator memoryOrchestrator) {
        this.memoryOrchestrator = memoryOrchestrator;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        // Phase 4 起长期记忆唯一注入点已收敛到 AgentLoop 的 transient SystemMessage。
        // 此 preprocessor 仅保留给旧配置兼容，不再注入长期记忆。
        return List.of();
    }
}
