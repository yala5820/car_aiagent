package com.hirain.aiagent.tools.vehicle.chassis;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleChassisManager {

    private final VehicleStateMachine stateMachine;

    public VehicleChassisManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询底盘行驶模式（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getChassisStatus() {
        return stateMachine.getChassisStatus();
    }

    /**
     * 控制底盘行驶模式，用于适应雪地、越野、普通驾驶场景。
     * 当用户需要切换驾驶模式时调用此工具。
     */
    @Tool(name = "set_chassis_mode",
          value = "控制底盘行驶模式。当用户需要切换驾驶模式（雪地/越野/普通）时调用。")
    public String setChassisMode(
            @P("模式，可选：'普通模式'、'越野模式'、'雪地模式'") String mode) {
        return stateMachine.setChassisMode(mode);
    }
}
