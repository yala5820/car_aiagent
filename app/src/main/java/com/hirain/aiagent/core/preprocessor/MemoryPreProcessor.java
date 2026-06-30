package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.memory.MemoryOrchestrator;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 记忆注入预处理器 — 在每轮 LLM 调用前注入长期记忆上下文。
 * <p>
 * 仅在首轮迭代（iteration = 0）时注入，避免重复。
 */
public class MemoryPreProcessor implements PreProcessor {

    private final MemoryOrchestrator memoryOrchestrator;

    public MemoryPreProcessor(MemoryOrchestrator memoryOrchestrator) {
        this.memoryOrchestrator = memoryOrchestrator;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        // 仅在首轮注入长期记忆
        if (ctx.iteration() > 0) {
            return List.of();
        }

        String userId = ctx.getContextData("user_id", String.class);
        if (userId == null) userId = "default_user";

        String longTermCtx = memoryOrchestrator.getUserContextDirect(userId) != null
                ? memoryOrchestrator.getUserContextDirect(userId).getLongTermContext()
                : "";

        if (longTermCtx.isEmpty()) {
            return List.of();
        }
        return List.of(UserMessage.from("【用户记忆参考】" + longTermCtx));
    }
}
