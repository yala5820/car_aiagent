package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * V2 可审计的确定性知识需求规则。使用“文档专属意图”与
 * “车辆主题 + 知识表达”的组合规则，避免把普通车控动作误判为知识请求。
 */
public final class RuleBasedKnowledgeNeedDetector implements KnowledgeNeedDetector {
    private static final String VERSION = "knowledge-need-v2";
    private static final String[] EXPLICIT_SOURCES = {
            "用户手册", "车主手册", "说明书", "使用说明", "操作指南", "官方资料", "官方说明",
            "查手册", "diy指南", "diy 指南", "owner manual", "owners manual", "official manual"
    };
    private static final String[] SERVICE_CENTER = {
            "服务中心", "快修店", "售后网点", "维修网点", "维修中心", "特斯拉中心",
            "售后电话", "售后地址"
    };
    private static final String[] WARRANTY = {
            "质保", "保修", "三包", "包修", "质量保证", "延保", "免费维修", "除外条款",
            "在保", "过保"
    };
    private static final String[] FAULT_TEXT = {
            "故障码", "故障代码", "提示文本", "告警灯", "警告灯", "报警灯", "仪表提示",
            "故障提示", "报错代码"
    };
    private static final String[] CONDITIONS = {
            "使用条件", "使用要求", "什么条件", "什么时候能用", "注意事项"
    };
    private static final String[] LIMITS = {
            "功能限制", "有什么限制", "为什么不能用", "为何不可用", "不能使用",
            "不可用原因", "是否支持", "能不能用"
    };
    private static final String[] VERSION_DIFFS = {"车型差异", "版本差异", "配置差异", "软件版本", "适用车型"};
    private static final String[] KNOWLEDGE_EXPRESSIONS = {
            "怎么用", "如何用", "怎样用", "怎么使用", "如何使用", "怎样使用",
            "怎么设置", "如何设置", "怎样设置", "在哪里设置", "操作步骤",
            "怎么开启", "如何开启", "怎样开启", "怎么关闭", "如何关闭",
            "怎么打开", "如何打开", "怎样打开", "有什么作用", "什么作用", "是什么功能",
            "如何更换", "怎么更换", "怎样更换", "更换方法", "多久更换", "更换周期",
            "如何安装", "怎么安装", "怎样安装", "安装方法",
            "如何拆卸", "怎么拆卸", "怎样拆卸", "拆卸方法",
            "如何校准", "怎么校准", "怎样校准", "如何检查", "怎么检查", "怎样检查",
            "如何调节", "怎么调节", "怎样调节",
            "如何保养", "怎么保养", "保养周期", "如何维护", "怎么维护", "维护周期",
            "如何清洁", "怎么清洁", "如何清洗", "怎么清洗", "如何加注", "怎么加注",
            "如何重启", "怎么重启", "如何恢复", "怎么恢复", "如何配对", "怎么配对",
            "为什么", "为何", "什么原因", "什么意思", "是否正常", "正常吗", "有没有问题",
            "怎么办", "怎么解决", "如何解决", "怎样解决", "如何处理", "怎么处理",
            "how to use", "how to set", "how to enable", "how to replace", "how to install",
            "how to remove", "how to maintain", "how to clean", "what does", "why"
    };
    private static final String[] VEHICLE_KNOWLEDGE_SUBJECTS = {
            "model y", "modely", "特斯拉", "车辆", "汽车", "本车",
            "钥匙", "车门", "车窗", "前备箱", "后备箱", "座椅", "安全带", "气囊",
            "换挡", "方向盘", "后视镜", "车灯", "雨刮", "雨刷", "制动", "驻车",
            "巡航控制", "主动巡航", "辅助转向", "智能泊车", "召唤", "车道辅助", "防撞辅助",
            "行车记录仪", "哨兵模式", "摄像头", "驾驶室摄像头", "触摸屏", "语音命令",
            "蓝牙", "wi-fi", "wifi", "手机app", "手机 app", "软件更新",
            "空调", "温度控制", "通风口", "导航", "地图", "媒体",
            "充电口", "充电器", "充电", "高压电池", "低压电池", "驱动电机", "续航", "能耗",
            "滤清器", "滤芯", "hepa", "轮胎", "胎压", "胎纹", "轮毂", "螺母罩",
            "雨刮器片", "制动液", "冷却液", "清洗液", "挡泥板", "车顶行李架", "拖车臂",
            "道路救援", "跳线启动", "浸水", "拖车", "运输",
            "cabin air filter", "tire", "wiper", "brake fluid", "coolant", "charging"
    };
    private static final String[] TROUBLE_STATES = {
            "故障", "异常", "警报", "报警", "警告", "报错", "失灵", "没反应", "不工作",
            "无法", "不能", "连不上", "打不开", "关不上", "启动不了", "不显示",
            "黑屏", "红灯", "异响", "抖动", "一直提示", "突然停止"
    };
    private static final String[] REALTIME_MARKERS = {
            "现在", "当前", "此刻", "实时", "还剩", "关了吗", "开着吗", "锁了吗"
    };
    private static final String[] VEHICLE_ACTIONS = {"打开", "关闭", "开启", "调高", "调低", "切换", "设置", "升起", "降下"};
    private static final String[] VEHICLE_OBJECTS = {
            "空调", "车窗", "窗户", "座椅", "车门", "后备箱", "前备箱",
            "底盘", "悬架", "驾驶模式", "香氛", "车灯", "雨刮", "哨兵模式"
    };
    private static final String[] COMPOUND_CONNECTORS = {"然后", "并且", "再帮我", "之后再", "同时帮我"};
    private static final Pattern FAULT_CODE = Pattern.compile("(?i).*(?:\\b[a-z]{1,3}[- ]?\\d{3,5}\\b|\\bp\\d{4}\\b).*" );

    @Override public KnowledgeIntentDecision decide(String userText, IntentResult intentResult) {
        String text = normalize(userText);
        if (text.isEmpty()) return KnowledgeIntentDecision.none("KNOWLEDGE_EMPTY_TEXT");
        String reason = requiredReason(text, intentResult);
        if (reason == null) return KnowledgeIntentDecision.none("KNOWLEDGE_NOT_REQUIRED");
        // 只有明确包含“先查询、再执行”的连接结构才是复合请求；“空调怎么设置”仍是纯知识问法。
        boolean compound = containsAny(text, COMPOUND_CONNECTORS)
                && containsAny(text, VEHICLE_ACTIONS)
                && (containsAny(text, VEHICLE_OBJECTS) || isVehicleIntent(intentResult));
        return new KnowledgeIntentDecision(KnowledgeRequirement.REQUIRED, reason, VERSION, compound);
    }

    private static String requiredReason(String text, IntentResult intentResult) {
        if (containsAny(text, EXPLICIT_SOURCES)) return "KNOWLEDGE_OFFICIAL_SOURCE";
        if (containsAny(text, SERVICE_CENTER)) return "KNOWLEDGE_SERVICE_CENTER";
        if (containsAny(text, WARRANTY)) return "KNOWLEDGE_WARRANTY";
        if (containsAny(text, FAULT_TEXT) || FAULT_CODE.matcher(text).matches()) return "KNOWLEDGE_FAULT_OR_PROMPT";
        if (containsAny(text, CONDITIONS)) return "KNOWLEDGE_USAGE_CONDITION";
        if (containsAny(text, LIMITS)) return "KNOWLEDGE_LIMITATION";
        if (containsAny(text, VERSION_DIFFS)) return "KNOWLEDGE_VERSION_DIFFERENCE";
        boolean vehicleSubject = containsAny(text, VEHICLE_KNOWLEDGE_SUBJECTS) || isVehicleIntent(intentResult);
        if (!vehicleSubject) return null;
        // 明确实时状态只保持 AUTO 可见，不强制查静态资料；带“正常吗/怎么办”等知识表达时除外。
        if (containsAny(text, REALTIME_MARKERS) && !containsAny(text, KNOWLEDGE_EXPRESSIONS)) return null;
        if (containsAny(text, TROUBLE_STATES)
                && (containsAny(text, KNOWLEDGE_EXPRESSIONS) || hasQuestionForm(text))) {
            return "KNOWLEDGE_TROUBLESHOOTING";
        }
        if (containsAny(text, KNOWLEDGE_EXPRESSIONS)) {
            return "KNOWLEDGE_VEHICLE_USAGE_OR_MAINTENANCE";
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
    private static boolean hasQuestionForm(String text) {
        return text.endsWith("?") || text.endsWith("？") || text.contains("怎么") || text.contains("如何")
                || text.contains("为何") || text.contains("为什么") || text.contains("什么意思");
    }
    private static boolean containsAny(String text, String[] values) { for (String value : values) if (text.contains(value)) return true; return false; }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
