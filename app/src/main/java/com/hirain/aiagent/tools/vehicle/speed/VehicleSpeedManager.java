package com.hirain.aiagent.tools.vehicle.speed;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

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

    /**
     * 调节车速大小。当用户需要设定巡航车速时调用。
     */
    @Tool(name = "set_vehicle_spd", value = "调节车速。当需要设定巡航车速时调用。")
    public String setVehicleSpd(@P("车速值，范围 0-240 km/h") int spd) {
        return stateMachine.setVehicleSpd(spd);
    }
}
