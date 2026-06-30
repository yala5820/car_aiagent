package com.hirain.aiagent.core.terminator;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.LoopTerminator;

import dev.langchain4j.model.chat.response.ChatResponse;

/** LLM 响应中无工具调用时终止循环。 */
public class NoToolCallTerminator implements LoopTerminator {
    @Override
    public boolean shouldStop(AgentLoopContext ctx, ChatResponse response) {
        return !response.aiMessage().hasToolExecutionRequests();
    }
}
