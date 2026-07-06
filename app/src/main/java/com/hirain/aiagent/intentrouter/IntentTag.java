package com.hirain.aiagent.intentrouter;

/**
 * 粗粒度意图标签 — 标识用户文本请求对应的业务领域。
 * <p>
 * 用于 AgentRuntime 在调用 AgentLoopOrchestrator 前对请求做轻量分类，
 * 不做工具调用分流，仅用于可观测性和后续策略准备。
 */
public enum IntentTag {
    CHAT,
    VEHICLE_AC,
    VEHICLE_WINDOW,
    VEHICLE_SEAT,
    VEHICLE_DOOR,
    VEHICLE_CHASSIS,
    VEHICLE_FRAGRANCE,
    VEHICLE_DMS,
    VISION_QA,
    WEATHER,
    UNKNOWN
}
