package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PostProcessor;

/**
 * 记忆提取后处理器 — 占位保留，实际的记忆提取由 AgentLoopOrchestrator
 * 在循环中统一调用 MemoryOrchestrator.onTurnComplete()。
 * <p>
 * 保留此类在 PostProcessor 链中的作用是确保配置完整、
 * 后续可为自定义处理逻辑扩展。
 */
public class MemoryPostProcessor implements PostProcessor {

    @Override
    public String process(String llmOutput, AgentLoopContext ctx) {
        return llmOutput;
    }
}
