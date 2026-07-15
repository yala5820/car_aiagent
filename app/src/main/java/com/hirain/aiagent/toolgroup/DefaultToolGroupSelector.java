package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import java.util.List;

/**
 * 基于 IntentResult 的默认 ToolGroup 选择器。
 * <p>
 * 将粗粒度 IntentTag 映射到受限 ToolGroup 列表。
 * 无法确定具体车控目标时返回澄清状态；选择输入异常时失败关闭，绝不暴露全量工具。
 */
public class DefaultToolGroupSelector implements ToolGroupSelector {

    private final ToolGroupRegistry registry;

    public DefaultToolGroupSelector(ToolGroupRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolGroupSelectionResult select(IntentResult intentResult, String userInput) {
        if (intentResult == null || intentResult.intentTag() == null) {
            return ToolGroupSelectionResult.failedClosed("selector:null_intent");
        }
        String text = textFor(intentResult, userInput);
        IntentConfidence confidence = intentResult.confidence() != null
                ? intentResult.confidence()
                : IntentConfidence.NONE;
        IntentTag tag = intentResult.intentTag();
        switch (tag) {
            case VEHICLE_AC:
                return selected(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_WINDOW:
                return selected(List.of(ToolGroupId.WINDOW_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_SEAT:
                return selected(List.of(ToolGroupId.SEAT_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_DOOR:
                return selected(List.of(ToolGroupId.DOOR_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_CHASSIS:
                return selected(List.of(ToolGroupId.CHASSIS_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_FRAGRANCE:
                return selected(List.of(ToolGroupId.FRAGRANCE_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case VEHICLE_DMS:
                return selected(List.of(ToolGroupId.DMS_GROUP, ToolGroupId.BASIC_STATUS_GROUP), tag, confidence);
            case WEATHER:
                return selected(List.of(ToolGroupId.WEATHER_GROUP), tag, confidence);
            case VISION_QA:
                return selected(List.of(ToolGroupId.VISION_GROUP), tag, confidence);
            case CHAT:
                return hasWeakVehicleKeyword(text)
                        ? ToolGroupSelectionResult.clarificationRequired(
                                "clarification:chat_vehicle_keyword", confidence)
                        : ToolGroupSelectionResult.chatOnly("intent:CHAT", confidence);
            case UNKNOWN:
            default:
                return hasWeakVehicleKeyword(text)
                        ? ToolGroupSelectionResult.clarificationRequired(
                                "clarification:unknown_vehicle_keyword", confidence)
                        : ToolGroupSelectionResult.chatOnly("intent:UNKNOWN_CHAT_ONLY", confidence);
        }
    }

    private ToolGroupSelectionResult selected(List<ToolGroupId> groupIds,
                                               IntentTag tag,
                                               IntentConfidence confidence) {
        return ToolGroupSelectionResult.selected(
                registry, groupIds, "intent:" + tag.name(), confidence);
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
