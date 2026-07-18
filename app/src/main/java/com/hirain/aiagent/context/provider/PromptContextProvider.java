package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextErrorCode;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.vision.routing.VisionRequirement;

import java.util.List;
import java.util.Map;

/**
 * System Prompt 上下文 Provider — 渲染当前 persona 的 system prompt 作为唯一 System Contribution。
 */
public class PromptContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "PromptContextProvider";
    }

    @Override public String sourceKey() { return com.hirain.aiagent.context.ContextPolicies.PROMPT; }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.REQUEST_STATIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input).required();
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String promptText;
        try {
            String templateName = PromptConstants.textPersonaTemplateName(session.personaId());
            if (input.promptManager() == null) {
                return ContextProviderResult.failure(name(),
                        "promptManager is null", ContextErrorCode.REQUIRED_PROVIDER_FAILED);
            }
            promptText = input.promptManager().render(templateName);
            if (promptText == null || promptText.trim().isEmpty()) {
                return ContextProviderResult.failure(name(),
                        "rendered prompt is empty", ContextErrorCode.REQUIRED_PROVIDER_FAILED);
            }
            if (session.visionIntentDecision().requirement() != VisionRequirement.NONE) {
                String grounding = input.promptManager().render(
                        PromptConstants.MSG_FRONT_VIEW_GROUNDING_POLICY);
                if (grounding == null || grounding.trim().isEmpty()) {
                    return ContextProviderResult.failure(name(), "vision grounding prompt is empty",
                            ContextErrorCode.REQUIRED_PROVIDER_FAILED);
                }
                promptText = promptText + "\n\n" + grounding;
            }
        } catch (Exception e) {
            return ContextProviderResult.failure(name(),
                    "prompt render failed: " + e.getMessage(),
                    ContextErrorCode.REQUIRED_PROVIDER_FAILED);
        }

        TextContextContribution contribution = new TextContextContribution(
                com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input),
                name(), TextContextContribution.TARGET_SYSTEM,
                promptText, Map.of("prompt_owner", "PromptContextProvider"));
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
