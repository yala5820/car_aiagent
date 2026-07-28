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

    private static final String[] CONTROL_ACTIONS = {
            "打开", "关闭", "开启", "关掉", "启动", "停止", "调高", "调低",
            "升高", "降低", "设置", "切换", "解锁", "上锁"
    };
    private static final String[] GENERIC_VEHICLE_TARGETS = {
            "车", "车辆", "汽车", "本车", "车里", "车内"
    };

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
                return hasAmbiguousVehicleControlRequest(text)
                        ? ToolGroupSelectionResult.clarificationRequired(
                                "clarification:chat_ambiguous_vehicle_control", confidence)
                        : ToolGroupSelectionResult.chatOnly("intent:CHAT", confidence);
            case UNKNOWN:
            default:
                return hasAmbiguousVehicleControlRequest(text)
                        ? ToolGroupSelectionResult.clarificationRequired(
                                "clarification:unknown_ambiguous_vehicle_control", confidence)
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

    /**
     * 只有用户明确表达“控制动作”，同时仅给出泛化车辆对象时才要求澄清。
     * 单纯出现“车”或车辆部件可能是知识问答，必须继续交给模型并保留只读知识工具。
     */
    static boolean hasAmbiguousVehicleControlRequest(String text) {
        if (text == null || text.isEmpty()) return false;
        return containsAny(text, CONTROL_ACTIONS) && containsAny(text, GENERIC_VEHICLE_TARGETS);
    }

    private static boolean containsAny(String text, String[] values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
