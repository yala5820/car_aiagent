package com.hirain.aiagent.tools.vehicle.speed;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleSpeedManager {

    private final VehicleStateMachine stateMachine;

    public VehicleSpeedManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询当前车速（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getSpeedStatus() {
        return stateMachine.getSpeedStatus();
    }
}
