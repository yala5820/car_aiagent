package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * System Prompt 上下文 Provider — HYBRID 模式不渲染，不调用 PromptManager.render()。
 * <p>
 * 一期只记录 persona 到 prompt 模板的映射关系，system prompt 注入仍由
 * AgentLoopOrchestrator.injectSystemPrompt 负责。
 */
public class PromptContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "PromptContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.PROMPT;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String templateName = PromptConstants.textPersonaTemplateName(session.personaId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("prompt_owner", "AgentLoopOrchestrator.injectSystemPrompt");
        metadata.put("prompt_template_name", templateName);
        metadata.put("prompt_injected_by_context", false);

        // HYBRID 模式：不渲染 system prompt，system prompt 注入由 AgentLoopOrchestrator 完成
        ContextSection section = new ContextSection(
                type(), name(), false, "", 0, false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
