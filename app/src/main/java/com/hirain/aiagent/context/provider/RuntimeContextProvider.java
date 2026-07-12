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
 * 运行时上下文 Provider — 输出 requestId/sessionId/userId/personaId/clientMessageId 等运行时元信息。
 * 贡献标记为 POLICY_ONLY，不进入模型消息，仅用于 Trace/Policy 诊断。
 */
public class RuntimeContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "RuntimeContextProvider";
    }

    @Override
    public ContextLifecycle lifecycle() {
        return ContextLifecycle.REQUEST_STATIC;
    }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        // TEXT 路径始终必需；校验身份在 provide 中完成
        return true;
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

        TextContextContribution contribution = new TextContextContribution(
                "runtime", ContextVisibility.POLICY_ONLY, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.CRITICAL, ContextLifecycle.REQUEST_STATIC, true,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                content, metadata);
        return ContextProviderResult.success(name(), List.of(contribution));
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
