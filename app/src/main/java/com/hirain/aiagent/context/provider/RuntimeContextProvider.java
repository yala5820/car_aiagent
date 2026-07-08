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
 * 运行时上下文 Provider — 输出 requestId/sessionId/userId/personaId/clientMessageId 等运行时元信息。
 */
public class RuntimeContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "RuntimeContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.RUNTIME;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String content = buildContent(session);
        int charCount = content.length();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("request_id", session.requestId());
        metadata.put("user_id", session.userId());
        metadata.put("session_id", session.sessionId());
        metadata.put("persona_id", session.personaId());
        metadata.put("client_message_id", session.clientMessageId());
        metadata.put("input_type", session.inputType());

        ContextSection section = new ContextSection(
                type(), name(), true, content, charCount, false, metadata);
        return ContextProviderResult.success(name(), section);
    }

    private static String buildContent(RequestSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("【运行时上下文】").append("\n");
        sb.append("- requestId: ").append(nonNull(session.requestId())).append("\n");
        sb.append("- userId: ").append(nonNull(session.userId())).append("\n");
        sb.append("- sessionId: ").append(nonNull(session.sessionId())).append("\n");
        sb.append("- personaId: ").append(nonNull(session.personaId())).append("\n");
        sb.append("- clientMessageId: ").append(nonNull(session.clientMessageId())).append("\n");
        sb.append("- inputType: ").append(nonNull(session.inputType()));
        return sb.toString();
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
