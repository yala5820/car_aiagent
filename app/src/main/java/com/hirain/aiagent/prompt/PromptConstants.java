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
}
