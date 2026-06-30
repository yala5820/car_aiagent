package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.memory.MemoryOrchestrator;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 记忆提取后处理器 — 在每轮 LLM 响应后触发长期记忆提取和压缩检查。
 */
public class MemoryPostProcessor implements PostProcessor {

    private final MemoryOrchestrator memoryOrchestrator;

    public MemoryPostProcessor(MemoryOrchestrator memoryOrchestrator) {
        this.memoryOrchestrator = memoryOrchestrator;
    }

    @Override
    public String process(String llmOutput, AgentLoopContext ctx) {
        String userId = ctx.getContextData("user_id", String.class);
        if (userId == null) userId = "default_user";

        // 触发记忆提取和压缩检查
        // 注意：currentMessages 和 tokenEstimate 在 PostProcessor 中不可直接获取，
        // 因此实际的压缩检查由 AgentLoopOrchestrator 在 execute() 中调用
        memoryOrchestrator.onTurnComplete(
                userId,
                List.of(),    // 消息列表由 Orchestrator 传递
                0,            // token 估算由 Orchestrator 传递
                ctx.userInput(),
                llmOutput
        );

        return llmOutput;
    }
}
