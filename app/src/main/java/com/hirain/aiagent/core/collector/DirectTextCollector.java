package com.hirain.aiagent.core.collector;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.AgentResult;
import com.hirain.aiagent.core.component.ResultCollector;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 直接返回 LLM 文本（chat、vision_qa persona 使用）。 */
public class DirectTextCollector implements ResultCollector {
    @Override
    public AgentResult collect(ChatResponse response, AgentLoopContext ctx) {
        String text = response.aiMessage().text();
        long duration = System.currentTimeMillis() - ctx.startTimeMs();
        return AgentResult.success(text, ctx.iteration() + 1, duration,
                ctx.toolExecutionHistory());
    }
}
