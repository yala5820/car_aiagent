package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 意图上下文 Provider — 输出 IntentRouter 的意图识别结果。
 */
public class IntentContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "IntentContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.INTENT;
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
        int charCount = content.length();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent_tag", intentTag);
        metadata.put("confidence", confidence);
        metadata.put("matched_keywords", keywords);

        ContextSection section = new ContextSection(
                type(), name(), true, content, charCount, false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
