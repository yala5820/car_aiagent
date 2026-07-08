package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人格上下文 Provider — 输出请求的 persona 信息。
 * <p>
 * 不使用 InputType/INTENT 等路由逻辑重新 normalize personaId，
 * 直接使用 {@link RequestSession#personaId()} 的值。
 */
public class PersonaContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "PersonaContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.PERSONA;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String personaId = session.personaId() != null ? session.personaId() : "chat";
        String content = "【人格上下文】\n"
                + "- requestedPersonaId: " + personaId + "\n"
                + "- effectivePersonaId: " + personaId;
        int charCount = content.length();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("persona_id", personaId);

        ContextSection section = new ContextSection(
                type(), name(), true, content, charCount, false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
