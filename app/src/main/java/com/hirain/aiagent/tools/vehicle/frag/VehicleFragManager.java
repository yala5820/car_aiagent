package com.hirain.aiagent.tools.vehicle.frag;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleFragManager {

    private final VehicleStateMachine stateMachine;

    public VehicleFragManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询香氛状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getFragStatus() {
        return stateMachine.getFragStatus();
    }

    /**
     * 控制车载香氛类型。当用户需要切换香氛气味时调用。
     */
    @Tool(name = "set_frag_type", value = "控制车载香氛类型。当用户需要切换香氛气味时调用。")
    public String setFragType(
            @P("类型，可选：'晨间松木'、'正午丁香'、'午夜橙香'") String type) {
        return stateMachine.setFragType(type);
    }

    /**
     * 控制车载香氛强度。当用户需要调节香氛浓度时调用。
     */
    @Tool(name = "set_frag_intensity", value = "控制车载香氛强度。当用户需要调节香氛浓度时调用。")
    public String setFragIntensity(
            @P("强度，可选：'关闭'、'低'、'中'、'高'") String intensity) {
        return stateMachine.setFragIntensity(intensity);
    }
}
