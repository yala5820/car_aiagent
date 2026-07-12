package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import java.util.List;

/**
 * 基于 IntentResult 的默认 ToolGroup 选择器。
 * <p>
 * 将粗粒度 IntentTag 映射到候选 ToolGroup 列表。
 * 无法判断意图时兜底返回 ALL_SAFE_DEMO_GROUP + 全量候选 toolName。
 */
public class DefaultToolGroupSelector implements ToolGroupSelector {

    private final ToolGroupRegistry registry;

    public DefaultToolGroupSelector(ToolGroupRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolGroupSelectionResult select(IntentResult intentResult, String userInput) {
        if (intentResult == null || intentResult.intentTag() == null) {
            return buildResult(List.of(ToolGroupId.ALL_SAFE_DEMO_GROUP),
                    "fallback:null_intent_all_tools", IntentConfidence.NONE, true);
        }
        String text = textFor(intentResult, userInput);
        List<ToolGroupId> groupIds = groupIdsFor(intentResult.intentTag(), text);
        boolean fallback = intentResult.intentTag() == IntentTag.UNKNOWN
                || (intentResult.intentTag() == IntentTag.CHAT && hasWeakVehicleKeyword(text));
        String reason = reasonFor(intentResult.intentTag(), text);
        IntentConfidence confidence = intentResult.confidence() != null
                ? intentResult.confidence()
                : IntentConfidence.NONE;
        return buildResult(groupIds, reason, confidence, fallback);
    }

    /**
     * 通过 registry 补齐 requiredContextKeys、highestRiskLevel、allToolsFallback、containsAggregationGroup，
     * 确保 Context 能以统一方式读取元信息。
     */
    private ToolGroupSelectionResult buildResult(List<ToolGroupId> groupIds,
                                                  String reason,
                                                  IntentConfidence confidence,
                                                  boolean fallbackUsed) {
        return ToolGroupSelectionResult.enriched(registry, groupIds, reason, confidence, fallbackUsed);
    }

    private List<ToolGroupId> groupIdsFor(IntentTag tag, String text) {
        switch (tag) {
            case VEHICLE_AC:
                return List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_WINDOW:
                return List.of(ToolGroupId.WINDOW_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_SEAT:
                return List.of(ToolGroupId.SEAT_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_DOOR:
                return List.of(ToolGroupId.DOOR_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_CHASSIS:
                return List.of(ToolGroupId.CHASSIS_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_FRAGRANCE:
                return List.of(ToolGroupId.FRAGRANCE_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case VEHICLE_DMS:
                return List.of(ToolGroupId.DMS_GROUP, ToolGroupId.BASIC_STATUS_GROUP);
            case WEATHER:
                return List.of(ToolGroupId.WEATHER_GROUP);
            case VISION_QA:
                return List.of(ToolGroupId.VISION_GROUP);
            case CHAT:
                return hasWeakVehicleKeyword(text)
                        ? List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP)
                        : List.of(ToolGroupId.CHAT_ONLY_GROUP);
            case UNKNOWN:
            default:
                return hasWeakVehicleKeyword(text)
                        ? List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP)
                        : List.of(ToolGroupId.ALL_SAFE_DEMO_GROUP);
        }
    }

    private String reasonFor(IntentTag tag, String text) {
        if (tag == IntentTag.UNKNOWN) {
            return hasWeakVehicleKeyword(text)
                    ? "fallback:unknown_vehicle_keyword"
                    : "fallback:unknown_all_tools";
        }
        if (tag == IntentTag.CHAT && hasWeakVehicleKeyword(text)) {
            return "fallback:chat_vehicle_keyword";
        }
        return "intent:" + tag.name();
    }

    private static String textFor(IntentResult intentResult, String userInput) {
        if (intentResult.normalizedText() != null && !intentResult.normalizedText().isEmpty()) {
            return intentResult.normalizedText();
        }
        return userInput != null ? userInput.trim().toLowerCase() : "";
    }

    static boolean hasWeakVehicleKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("车") || text.contains("空调") || text.contains("车窗")
                || text.contains("窗户") || text.contains("座椅") || text.contains("车门")
                || text.contains("底盘") || text.contains("悬架") || text.contains("香氛")
                || text.contains("香薰") || text.contains("dms") || text.contains("驾驶员");
    }
}
