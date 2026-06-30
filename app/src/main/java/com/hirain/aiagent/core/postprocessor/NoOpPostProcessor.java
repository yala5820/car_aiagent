package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PostProcessor;

/** 直通后处理器 — 不修改 LLM 输出（chat persona 使用）。 */
public class NoOpPostProcessor implements PostProcessor {
    @Override
    public String process(String llmOutput, AgentLoopContext ctx) {
        return llmOutput;
    }
}
