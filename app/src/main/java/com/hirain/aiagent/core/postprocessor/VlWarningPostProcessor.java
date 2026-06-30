package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

/**
 * 在视觉问答响应后追加记忆失效警告。
 */
public class VlWarningPostProcessor implements PostProcessor {

    private final PromptManager promptManager;

    public VlWarningPostProcessor(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String process(String llmOutput, AgentLoopContext ctx) {
        String warning = promptManager.render(PromptConstants.MSG_VL_WARNING);
        return llmOutput + warning;
    }
}
