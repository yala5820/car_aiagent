package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * V1 可审计的确定性知识需求规则。规则宁可不把普通动作升级为知识请求，
 * 因为 REQUIRED 会收窄本轮能力；复杂语义判断留给后续受评测的模型路由设计。
 */
public final class RuleBasedKnowledgeNeedDetector implements KnowledgeNeedDetector {
    private static final String VERSION = "knowledge-need-v1";
    private static final String[] EXPLICIT_SOURCES = {
            "用户手册", "说明书", "官方资料", "官方说明", "查手册",
            "owner manual", "owners manual", "official manual"
    };
    private static final String[] FAULT_TEXT = {"故障码", "故障代码", "提示文本", "告警灯", "报警灯", "仪表提示", "故障提示"};
    private static final String[] CONDITIONS = {"使用条件", "使用要求", "什么条件", "什么时候能用", "如何使用", "注意事项"};
    private static final String[] LIMITS = {"功能限制", "有什么限制", "为什么不能用", "为何不可用", "不能使用", "不可用原因"};
    private static final String[] VERSION_DIFFS = {"车型差异", "版本差异", "配置差异", "软件版本", "适用车型"};
    private static final String[] MAINTENANCE_PROCEDURES = {
            "如何更换", "怎么更换", "更换方法", "如何安装", "怎么安装", "安装方法",
            "如何拆卸", "怎么拆卸", "拆卸方法", "如何保养", "怎么保养",
            "如何维护", "怎么维护", "如何清洁", "怎么清洁", "如何清洗", "怎么清洗",
            "how to replace", "how to install", "how to remove", "how to maintain", "how to clean"
    };
    private static final String[] VEHICLE_KNOWLEDGE_SUBJECTS = {
            "model y", "modely", "特斯拉", "车辆", "汽车", "本车",
            "滤清器", "滤芯", "轮胎", "雨刮", "雨刷", "制动液", "冷却液",
            "空调", "车窗", "座椅", "车门", "底盘", "悬架", "充电", "后备箱", "前备箱",
            "cabin air filter", "tire", "wiper", "brake fluid", "coolant", "charging"
    };
    private static final String[] VEHICLE_ACTIONS = {"打开", "关闭", "开启", "调高", "调低", "切换", "设置", "升起", "降下"};
    private static final String[] VEHICLE_OBJECTS = {"空调", "车窗", "窗户", "座椅", "车门", "底盘", "悬架", "驾驶模式", "香氛"};
    private static final Pattern FAULT_CODE = Pattern.compile("(?i).*(?:\\b[a-z]{1,3}[- ]?\\d{3,5}\\b|\\bp\\d{4}\\b).*" );

    @Override public KnowledgeIntentDecision decide(String userText, IntentResult intentResult) {
        String text = normalize(userText);
        if (text.isEmpty()) return KnowledgeIntentDecision.none("KNOWLEDGE_EMPTY_TEXT");
        String reason = requiredReason(text, intentResult);
        if (reason == null) return KnowledgeIntentDecision.none("KNOWLEDGE_NOT_REQUIRED");
        // 车辆域标签本身不等于动作：例如“空调使用条件”必须正常进入知识检索，而不是被当作复合请求。
        boolean vehicleAction = containsAny(text, VEHICLE_ACTIONS)
                && (containsAny(text, VEHICLE_OBJECTS) || isVehicleIntent(intentResult));
        return new KnowledgeIntentDecision(KnowledgeRequirement.REQUIRED, reason, VERSION, vehicleAction);
    }

    private static String requiredReason(String text, IntentResult intentResult) {
        if (containsAny(text, EXPLICIT_SOURCES)) return "KNOWLEDGE_OFFICIAL_SOURCE";
        if (containsAny(text, FAULT_TEXT) || FAULT_CODE.matcher(text).matches()) return "KNOWLEDGE_FAULT_OR_PROMPT";
        if (containsAny(text, CONDITIONS)) return "KNOWLEDGE_USAGE_CONDITION";
        if (containsAny(text, LIMITS)) return "KNOWLEDGE_LIMITATION";
        if (containsAny(text, VERSION_DIFFS)) return "KNOWLEDGE_VERSION_DIFFERENCE";
        // 维护类问法必须同时具备车辆主题，避免“怎么更换头像”等普通操作误触发车辆知识库。
        if (containsAny(text, MAINTENANCE_PROCEDURES)
                && (containsAny(text, VEHICLE_KNOWLEDGE_SUBJECTS) || isVehicleIntent(intentResult))) {
            return "KNOWLEDGE_MAINTENANCE_PROCEDURE";
        }
        return null;
    }

    private static boolean isVehicleIntent(IntentResult result) {
        if (result == null) return false;
        IntentTag tag = result.intentTag();
        return tag == IntentTag.VEHICLE_AC || tag == IntentTag.VEHICLE_WINDOW || tag == IntentTag.VEHICLE_SEAT
                || tag == IntentTag.VEHICLE_DOOR || tag == IntentTag.VEHICLE_CHASSIS
                || tag == IntentTag.VEHICLE_FRAGRANCE || tag == IntentTag.VEHICLE_DMS;
    }
    private static boolean containsAny(String text, String[] values) { for (String value : values) if (text.contains(value)) return true; return false; }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
