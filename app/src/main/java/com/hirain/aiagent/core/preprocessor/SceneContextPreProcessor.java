package com.hirain.aiagent.core.preprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PreProcessor;
import com.hirain.aiagent.engines.scenematch.SceneMatch;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.prompt.PromptManager;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 注入场景描述作为临时上下文（scene persona 专用）。
 */
public class SceneContextPreProcessor implements PreProcessor {

    private final PromptManager promptManager;

    public SceneContextPreProcessor(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public List<ChatMessage> prepare(AgentLoopContext ctx) {
        SceneMatch.Scene scene = ctx.getContextData("scene", SceneMatch.Scene.class);
        String sceneDesc = scene != null ? scene.to_string() : "未知场景";
        String rendered = promptManager.render(PromptConstants.USER_SCENE_DESCRIPTION,
                Map.of("scene_description", sceneDesc));
        return List.of(UserMessage.from(rendered));
    }
}
