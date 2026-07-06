package com.hirain.aiagent.toolgroup;

/**
 * 工具组标识枚举 — 将现有 @Tool 按功能域分组。
 * <p>
 * 用于 ToolGroupRegistry 和 ToolGroupSelector 中标识候选工具组。
 * 本阶段不用于限制 LLM 可见工具，仅记录和观测。
 */
public enum ToolGroupId {
    CHAT_ONLY_GROUP,
    BASIC_STATUS_GROUP,
    AC_GROUP,
    WINDOW_GROUP,
    SEAT_GROUP,
    DOOR_GROUP,
    CHASSIS_GROUP,
    FRAGRANCE_GROUP,
    DMS_GROUP,
    WEATHER_GROUP,
    VISION_GROUP,
    COMMON_VEHICLE_GROUP,
    ALL_SAFE_DEMO_GROUP
}
