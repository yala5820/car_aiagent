package com.hirain.aiagent.vision.routing;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo 阶段可审计的视觉候选策略。
 * 该策略不取代粗粒度 IntentRouter；它专门负责判断是否允许或强制前向视觉 Tool。
 */
public final class RuleBasedVisionIntentPolicy implements VisionIntentPolicy {
    private static final String[] NEGATIONS = {"不要看摄像头", "不需要看画面", "不是让你看前方"};
    private static final String[] GENERIC_IMAGE = {"生成图片", "画一张", "图片格式", "照片编辑", "拍照技巧"};
    private static final String[] DEVICE_KNOWLEDGE = {"工作原理", "有什么作用", "介绍", "区别", "怎么安装", "坏了怎么办", "故障"};
    private static final String[] SPATIAL = {"前方", "前面", "车外", "挡风玻璃外", "路面", "路边", "眼前", "那里", "那个"};
    private static final String[] ACTIONS = {"看看", "看一下", "识别", "观察", "读一下", "是什么", "有什么", "能否看到", "看"};
    private static final String[] OBJECTS = {"前车", "行人", "路牌", "交通标志", "红绿灯", "车道", "障碍物"};
    private static final String[] DEVICES = {"前置摄像头", "前视摄像头", "摄像头画面"};
    private static final String[] VEHICLE_OBJECTS = {"空调", "车窗", "窗户", "座椅", "车门", "底盘", "悬架", "驾驶模式", "香氛"};
    private static final String[] VEHICLE_ACTIONS = {"打开", "关闭", "开启", "调高", "调低", "切换", "设置", "升", "降"};

    @Override
    public VisionIntentDecision decide(String userText, IntentResult intentResult) {
        String text = normalize(userText);
        if (text.isEmpty()) return VisionIntentDecision.none("empty_text");
        List<String> signals = new ArrayList<>();
        boolean negated = contains(text, NEGATIONS, signals, "negation:");
        boolean generic = contains(text, GENERIC_IMAGE, signals, "generic:");
        boolean action = contains(text, ACTIONS, signals, "action:");
        boolean deviceKnowledge = contains(text, DEVICE_KNOWLEDGE, signals, "knowledge:");
        if (negated || generic || (deviceKnowledge && !action)) {
            return new VisionIntentDecision(VisionRequirement.NONE, signals,
                    negated ? "explicit_negation" : generic ? "generic_image_request" : "device_knowledge", false);
        }
        boolean spatial = contains(text, SPATIAL, signals, "spatial:");
        boolean object = contains(text, OBJECTS, signals, "object:");
        boolean device = contains(text, DEVICES, signals, "device:");
        boolean vehicleText = containsAny(text, VEHICLE_OBJECTS) && containsAny(text, VEHICLE_ACTIONS);
        boolean vehicleRouter = intentResult != null && isVehicle(intentResult.intentTag());
        boolean compound = (spatial || device || object) && action && (vehicleText || vehicleRouter);
        if (compound) {
            signals.add("compound:vehicle_control");
            return new VisionIntentDecision(VisionRequirement.NONE, signals, "compound_vision_vehicle", true);
        }
        if ((device && action) || (device && (text.contains("用") || text.contains("帮我")))) {
            return new VisionIntentDecision(VisionRequirement.REQUIRED, signals, "explicit_front_camera", false);
        }
        if ((spatial && action) || (spatial && object)) {
            return new VisionIntentDecision(VisionRequirement.REQUIRED, signals, "front_view_request", false);
        }
        if (action || object || spatial) {
            return new VisionIntentDecision(VisionRequirement.OPTIONAL, signals, "ambiguous_visual_reference", false);
        }
        return VisionIntentDecision.none("not_visual");
    }

    private static boolean isVehicle(IntentTag tag) {
        return tag == IntentTag.VEHICLE_AC || tag == IntentTag.VEHICLE_WINDOW
                || tag == IntentTag.VEHICLE_SEAT || tag == IntentTag.VEHICLE_DOOR
                || tag == IntentTag.VEHICLE_CHASSIS || tag == IntentTag.VEHICLE_FRAGRANCE
                || tag == IntentTag.VEHICLE_DMS;
    }

    private static boolean contains(String text, String[] tokens, List<String> signals, String prefix) {
        boolean matched = false;
        for (String token : tokens) {
            if (text.contains(token)) { signals.add(prefix + token); matched = true; }
        }
        return matched;
    }

    private static boolean containsAny(String text, String[] tokens) {
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase() : "";
    }
}
