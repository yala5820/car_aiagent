package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 注入主动控车指令（scene persona 专用）。
 */
public class ActiveControlPreProcessor implements PreProcessor {

    private final PromptManager promptManager;

    public ActiveControlPreProcessor(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        return List.of(UserMessage.from(
                promptManager.render(PromptConstants.USER_ACTIVE_CONTROL)));
    }
}
