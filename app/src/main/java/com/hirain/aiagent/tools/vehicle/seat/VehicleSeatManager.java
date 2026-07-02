package com.hirain.aiagent.tools.vehicle.seat;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleSeatManager {

    private final VehicleStateMachine stateMachine;

    public VehicleSeatManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询座椅、方向盘状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getSeatStatus() {
        return stateMachine.getSeatStatus();
    }

    // ── 座椅加热 ──

    @Tool(name = "set_seat_fl_heat", value = "控制左前座椅加热。当用户需要开启或关闭左前座椅加热时调用。")
    public String setSeatFlHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setSeatFlHeat(heat);
    }

    @Tool(name = "set_seat_fr_heat", value = "控制右前座椅加热。当用户需要开启或关闭右前座椅加热时调用。")
    public String setSeatFrHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setSeatFrHeat(heat);
    }

    @Tool(name = "set_seat_rl_heat", value = "控制左后座椅加热。当用户需要开启或关闭左后座椅加热时调用。")
    public String setSeatRlHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setSeatRlHeat(heat);
    }

    @Tool(name = "set_seat_rr_heat", value = "控制右后座椅加热。当用户需要开启或关闭右后座椅加热时调用。")
    public String setSeatRrHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setSeatRrHeat(heat);
    }

    // ── 座椅通风 ──

    @Tool(name = "set_seat_fl_air", value = "控制左前座椅通风。当用户需要调节左前座椅通风时调用。")
    public String setSeatFlAir(@P("通风百分比，范围 0-100") int air) {
        return stateMachine.setSeatFlAir(air);
    }

    @Tool(name = "set_seat_fr_air", value = "控制右前座椅通风。当用户需要调节右前座椅通风时调用。")
    public String setSeatFrAir(@P("通风百分比，范围 0-100") int air) {
        return stateMachine.setSeatFrAir(air);
    }

    @Tool(name = "set_seat_rl_air", value = "控制左后座椅通风。当用户需要调节左后座椅通风时调用。")
    public String setSeatRlAir(@P("通风百分比，范围 0-100") int air) {
        return stateMachine.setSeatRlAir(air);
    }

    @Tool(name = "set_seat_rr_air", value = "控制右后座椅通风。当用户需要调节右后座椅通风时调用。")
    public String setSeatRrAir(@P("通风百分比，范围 0-100") int air) {
        return stateMachine.setSeatRrAir(air);
    }

    // ── 座椅按摩 ──

    @Tool(name = "set_seat_massage_mode",
          value = "控制主驾座椅按摩模式。当用户需要切换按摩模式时调用。")
    public String setSeatMassageMode(
            @P("模式，可选：'波浪'、'脉冲'、'揉捏'、'震动'、'腰部聚焦'") String mode) {
        return stateMachine.setSeatMassageMode(mode);
    }

    @Tool(name = "set_seat_massage_intensity",
          value = "控制主驾座椅按摩强度。当用户需要调节按摩力度时调用。")
    public String setSeatMassageIntensity(
            @P("强度，可选：'关闭'、'弱'、'中等'、'强力'") String intensity) {
        return stateMachine.setSeatMassageIntensity(intensity);
    }

    // ── 方向盘 ──

    @Tool(name = "set_steering_heat", value = "控制方向盘加热。当用户需要开启或关闭方向盘加热时调用。")
    public String setSteeringHeat(@P("true 开启，false 关闭") boolean heat) {
        return stateMachine.setSteeringHeat(heat);
    }
}
