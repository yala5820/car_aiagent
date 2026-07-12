package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人格上下文 Provider — 输出请求的 persona 信息。
 * 贡献标记为 POLICY_ONLY，不进入模型消息，仅用于 Trace/Policy 记录有效人格。
 */
public class PersonaContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "PersonaContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.REQUEST_STATIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return false;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String personaId = session.personaId() != null ? session.personaId() : "chat";
        String content = "【人格上下文】\n"
                + "- requestedPersonaId: " + personaId + "\n"
                + "- effectivePersonaId: " + personaId;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("persona_id", personaId);

        TextContextContribution contribution = new TextContextContribution(
                "persona", ContextVisibility.POLICY_ONLY, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.NORMAL, ContextLifecycle.REQUEST_STATIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                content, metadata);
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
