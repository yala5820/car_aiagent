package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 注入 VL 前向视野记忆失效警告（vision_qa persona 专用）。
 */
public class VlWarningPreProcessor implements PreProcessor {

    private final PromptManager promptManager;

    public VlWarningPreProcessor(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        return List.of(UserMessage.from(
                promptManager.render(PromptConstants.MSG_VL_WARNING)));
    }
}
