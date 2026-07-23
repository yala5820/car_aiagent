package com.hirain.aiagent.prompt;

/**
 * 模板名称常量 — 所有 Prompt 模板的引用路径集中定义在此处。
 * 路径相对于 assets/prompts/ 根目录，不包含 .txt 后缀。
 */
public final class PromptConstants {

    private PromptConstants() {}

    // ── 系统提示词 ──

    /** 默认车载 AI 助手系统提示词 */
    public static final String SYSTEM_ASSISTANT_DEFAULT = "system/assistant_default";

    /** 友好风格系统提示词 */
    public static final String SYSTEM_ASSISTANT_FRIENDLY = "system/assistant_friendly";

    /** 简洁风格系统提示词 */
    public static final String SYSTEM_ASSISTANT_CONCISE = "system/assistant_concise";

    /** 场景服务专用系统提示词 */
    public static final String SYSTEM_ASSISTANT_SCENE = "system/assistant_scene";

    // ── 任务提示词 ──

    /** 场景识别（VL → JSON） */
    public static final String TASK_SCENE_RECOGNITION = "task/scene_recognition";

    /** 前向视野问答 */
    public static final String TASK_FRONT_VIEW_QA = "task/front_view_qa";

    // ── 用户消息模板 ──

    /** 车辆状态注入 {{vehicle_status}} */
    public static final String USER_VEHICLE_STATUS = "user/vehicle_status";

    /** 场景描述注入 {{scene_description}} */
    public static final String USER_SCENE_DESCRIPTION = "user/scene_description";

    /** 主动控车指令（场景触发） */
    public static final String USER_ACTIVE_CONTROL = "user/active_control";

    /** 文本整合 {{first_part}} {{second_part}} */
    public static final String USER_SUMMARIZE = "user/summarize";

    // ── 消息片段 ──

    /** VL 响应后缀警告 */
    public static final String MSG_VL_WARNING = "messages/vl_warning";

    /** 前向视觉 Tool 证据约束（仅 TEXT 视觉候选请求追加） */
    public static final String MSG_FRONT_VIEW_GROUNDING_POLICY = "messages/front_view_grounding_policy";

    /** 车辆知识 Evidence 约束（仅必须检索的 TEXT 请求追加） */
    public static final String MSG_VEHICLE_KNOWLEDGE_GROUNDING_POLICY = "messages/vehicle_knowledge_grounding_policy";

    /**
     * 根据 TEXT persona 选择系统提示词模板。
     * <p>
     * 设计原因：AgentConfigFactory 和 Context 的 PromptContextProvider 都需要记录同一映射，
     * 映射必须集中维护，避免新增 persona 时出现两处硬编码不一致。
     */
    public static String textPersonaTemplateName(String personaId) {
        if ("friendly".equals(personaId)) return SYSTEM_ASSISTANT_FRIENDLY;
        if ("concise".equals(personaId)) return SYSTEM_ASSISTANT_CONCISE;
        return SYSTEM_ASSISTANT_DEFAULT;
    }
}
