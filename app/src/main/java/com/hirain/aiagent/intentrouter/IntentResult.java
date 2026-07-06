package com.hirain.aiagent.intentrouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次意图识别结果 — 包含意图标签、置信度、匹配关键词及调试信息。
 * <p>
 * matchedKeywords 使用不可修改 List 防护，防止外部篡改。
 * normalizedText 仅用于调试和日志，不回写 AgentRequest.text。
 */
public final class IntentResult {

    private final IntentTag intentTag;
    private final IntentConfidence confidence;
    private final List<String> matchedKeywords;
    private final String normalizedText;
    private final String sourceInputType;
    private final String debugReason;

    private IntentResult(IntentTag intentTag, IntentConfidence confidence,
                         List<String> matchedKeywords, String normalizedText,
                         String sourceInputType, String debugReason) {
        this.intentTag = intentTag;
        this.confidence = confidence;
        this.matchedKeywords = Collections.unmodifiableList(new ArrayList<>(matchedKeywords));
        this.normalizedText = normalizedText;
        this.sourceInputType = sourceInputType;
        this.debugReason = debugReason;
    }

    // ── 工厂方法 ──

    public static IntentResult of(IntentTag intentTag, IntentConfidence confidence,
                                  List<String> matchedKeywords, String normalizedText,
                                  String sourceInputType, String debugReason) {
        return new IntentResult(intentTag, confidence, matchedKeywords,
                normalizedText, sourceInputType, debugReason);
    }

    public static IntentResult unknown(String normalizedText, String sourceInputType, String debugReason) {
        return new IntentResult(IntentTag.UNKNOWN, IntentConfidence.NONE,
                List.of(), normalizedText, sourceInputType, debugReason);
    }

    // ── 读取器 ──

    public IntentTag intentTag() { return intentTag; }
    public IntentConfidence confidence() { return confidence; }
    public List<String> matchedKeywords() { return matchedKeywords; }
    public String normalizedText() { return normalizedText; }
    public String sourceInputType() { return sourceInputType; }
    public String debugReason() { return debugReason; }
}
