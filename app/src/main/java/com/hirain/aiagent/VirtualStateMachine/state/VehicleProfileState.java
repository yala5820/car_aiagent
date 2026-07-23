package com.hirain.aiagent.VirtualStateMachine.state;

/**
 * 当前试运行车辆的可信静态事实。它不属于八个动态子系统，不能通过通用 Patch 或用户请求改写。
 *
 * RAG 的 Scope 必须从这里读取，而非从模型文本或调用方参数推断，避免车型、地区或配置不匹配的资料进入候选集。
 */
public final class VehicleProfileState {
    public final String vehicleModel = "MODEL_Y";
    public final String modelYear = "2026";
    public final String region = "CN";
    public final String softwareVersion = "2026_REFRESH";
    public final String configurationCode = "RWD";
    public final boolean demoProfile = false;
}
