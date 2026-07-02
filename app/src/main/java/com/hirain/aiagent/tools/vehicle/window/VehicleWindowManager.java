package com.hirain.aiagent.tools.vehicle.window;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleWindowManager {

    private final VehicleStateMachine stateMachine;

    public VehicleWindowManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询车窗、天窗、遮阳帘状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getWindowStatus() {
        return stateMachine.getWindowStatus();
    }

    @Tool(name = "set_fl_window_status", value = "调节左前车窗开度。当用户需要升降左前车窗时调用。")
    public String setFlWindowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setFlWindowStatus(status);
    }

    @Tool(name = "set_fr_window_status", value = "调节右前车窗开度。当用户需要升降右前车窗时调用。")
    public String setFrWindowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setFrWindowStatus(status);
    }

    @Tool(name = "set_rl_window_status", value = "调节左后车窗开度。当用户需要升降左后车窗时调用。")
    public String setRlWindowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setRlWindowStatus(status);
    }

    @Tool(name = "set_rr_window_status", value = "调节右后车窗开度。当用户需要升降右后车窗时调用。")
    public String setRrWindowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setRrWindowStatus(status);
    }

    @Tool(name = "set_top_window_status", value = "调节天窗开度。当用户需要打开或关闭天窗时调用。")
    public String setTopWindowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setTopWindowStatus(status);
    }

    @Tool(name = "set_sun_shadow_status", value = "调节遮阳帘开度。当用户需要打开或关闭遮阳帘时调用。")
    public String setSunShadowStatus(@P("开度百分比，范围 0-100") int status) {
        return stateMachine.setSunShadowStatus(status);
    }

    @Tool(name = "set_window_f_defrosting", value = "控制前风挡除霜功能。当用户需要开启或关闭前风挡除霜时调用。")
    public String setWindowFDefrosting(@P("true 开启，false 关闭") boolean defrosting) {
        return stateMachine.setWindowFDefrosting(defrosting);
    }

    @Tool(name = "set_window_r_heat", value = "控制后风挡加热功能。当用户需要开启或关闭后风挡加热时调用。")
    public String setWindowRHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setWindowRHeat(heat);
    }

    @Tool(name = "set_mirror_l_heat", value = "控制左后视镜加热功能。当用户需要开启或关闭左后视镜加热时调用。")
    public String setMirrorLHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setMirrorLHeat(heat);
    }

    @Tool(name = "set_mirror_r_heat", value = "控制右后视镜加热功能。当用户需要开启或关闭右后视镜加热时调用。")
    public String setMirrorRHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setMirrorRHeat(heat);
    }

    @Tool(name = "set_no_window_opening_passengers",
          value = "控制乘员禁止开窗功能。当用户需要禁止后排乘客开窗时调用。")
    public String setNoWindowOpeningPassengers(@P("true 开启禁止，false 关闭禁止") boolean open) {
        return stateMachine.setNoWindowOpeningPassengers(open);
    }
}
