package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图上下文 Provider — 输出 IntentRouter 的意图识别结果。
 * 贡献标记为 POLICY_ONLY，不进入模型消息，仅用于 ToolGroupSelector 和 Trace 诊断。
 */
public class IntentContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "IntentContextProvider";
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
        IntentResult intent = session.intentResult();
        String intentTag = intent != null && intent.intentTag() != null
                ? intent.intentTag().name() : "UNKNOWN";
        String confidence = intent != null && intent.confidence() != null
                ? intent.confidence().name() : "NONE";
        String keywords = intent != null && intent.matchedKeywords() != null
                ? String.join(", ", intent.matchedKeywords()) : "";
        String debugReason = intent != null && intent.debugReason() != null
                ? intent.debugReason() : "";

        String content = "【意图上下文】\n"
                + "- intentTag: " + intentTag + "\n"
                + "- confidence: " + confidence + "\n"
                + "- matchedKeywords: " + keywords + "\n"
                + "- debugReason: " + debugReason;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent_tag", intentTag);
        metadata.put("confidence", confidence);
        metadata.put("matched_keywords", keywords);

        TextContextContribution contribution = new TextContextContribution(
                "intent", ContextVisibility.POLICY_ONLY, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.OPTIONAL, ContextLifecycle.REQUEST_STATIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                content, metadata);
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
