package com.hirain.aiagent.core.terminator;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.LoopTerminator;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 安全审查否决时终止循环。 */
public class SafetyVetoTerminator implements LoopTerminator {
    @Override
    public boolean shouldStop(AgentLoopContext ctx, ChatResponse response) {
        return ctx.lastSafetyVeto() != null && ctx.lastSafetyVeto().isVetoed();
    }
}
