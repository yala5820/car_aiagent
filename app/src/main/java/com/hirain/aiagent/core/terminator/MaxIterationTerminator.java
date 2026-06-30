package com.hirain.aiagent.core.terminator;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.LoopTerminator;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 达到最大迭代上限时终止循环。 */
public class MaxIterationTerminator implements LoopTerminator {

    private final int maxIterations;

    public MaxIterationTerminator(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    @Override
    public boolean shouldStop(AgentLoopContext ctx, ChatResponse response) {
        return ctx.iteration() >= maxIterations - 1;
    }
}
