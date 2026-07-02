package com.hirain.aiagent.tools.vehicle.ac;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleAcManager {

    private final VehicleStateMachine stateMachine;

    public VehicleAcManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询空调状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getAcStatus() {
        return stateMachine.getAcStatus();
    }

    // ── 空调开关 ──

    @Tool(name = "set_ac_status", value = "控制空调开启/关闭。当用户要求打开或关闭空调时调用。")
    public String setAcStatus(@P("true 开启，false 关闭") boolean status) {
        return stateMachine.setAcStatus(status);
    }

    // ── 温度 ──

    @Tool(name = "set_ac_drive_temp", value = "调节主驾空调温度。当用户需要调整主驾侧温度时调用。")
    public String setAcDriveTemp(@P("温度（摄氏度），范围 16-31") int temp) {
        return stateMachine.setAcDriveTemp(temp);
    }

    @Tool(name = "set_ac_assist_temp", value = "调节副驾空调温度。当用户需要调整副驾侧温度时调用。")
    public String setAcAssistTemp(@P("温度（摄氏度），范围 16-31") int temp) {
        return stateMachine.setAcAssistTemp(temp);
    }

    // ── 风量 ──

    @Tool(name = "set_ac_fan_intensity", value = "调节空调风量挡位。当用户需要调整风量大小时调用。")
    public String setAcFanIntensity(@P("风量挡位，范围 1-7（递增）") int intensity) {
        return stateMachine.setAcFanIntensity(intensity);
    }

    // ── 经济模式 ──

    @Tool(name = "set_ac_eco_mode", value = "控制空调经济模式。当用户需要开启或关闭经济模式时调用。")
    public String setAcEcoMode(@P("true 开启，false 关闭") boolean mode) {
        return stateMachine.setAcEcoMode(mode);
    }

    // ── 负离子 ──

    @Tool(name = "set_ac_anion_status", value = "控制负离子功能。当用户需要开启或关闭负离子时调用。")
    public String setAcAnionStatus(@P("true 开启，false 关闭") boolean status) {
        return stateMachine.setAcAnionStatus(status);
    }

    // ── 干燥除味 ──

    @Tool(name = "set_ac_clean_mode", value = "控制空调干燥除味模式。当用户需要清洁空调时调用。")
    public String setAcCleanMode(
            @P("模式，可选：'关闭'、'标准清洁'、'深度清洁'") String mode) {
        return stateMachine.setAcCleanMode(mode);
    }

    // ── 内外循环 ──

    @Tool(name = "set_ac_cyc_mode", value = "控制空调内外循环模式。当用户需要切换循环方式时调用。")
    public String setAcCycMode(
            @P("模式，可选：'内循环'、'外循环'、'自动'") String mode) {
        return stateMachine.setAcCycMode(mode);
    }

    // ── 自动扫风 ──

    @Tool(name = "set_ac_drive_sweep_auto", value = "控制主驾自动扫风功能。当用户需要开启或关闭主驾扫风时调用。")
    public String setAcDriveSweepAuto(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcDriveSweepAuto(auto);
    }

    @Tool(name = "set_ac_assist_sweep_auto", value = "控制副驾自动扫风功能。当用户需要开启或关闭副驾扫风时调用。")
    public String setAcAssistSweepAuto(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcAssistSweepAuto(auto);
    }

    // ── 出风口 ──

    @Tool(name = "set_ac_drive_left_air_outlet", value = "控制主驾左侧出风口。当用户需要开启或关闭主驾左侧出风口时调用。")
    public String setAcDriveLeftAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcDriveLeftAirOutlet(auto);
    }

    @Tool(name = "set_ac_drive_right_air_outlet", value = "控制主驾右侧出风口。当用户需要开启或关闭主驾右侧出风口时调用。")
    public String setAcDriveRightAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcDriveRightAirOutlet(auto);
    }

    @Tool(name = "set_ac_assist_air_outlet_mode",
          value = "控制副驾电动出风口模式。当用户需要切换副驾出风方向时调用。")
    public String setAcAssistAirOutletMode(
            @P("模式，可选：'AirManual OFF'、'Air Vertical'、'Air Horizontal'、'Air Point'、'Mirror Wind'、'One way'、'AirnoVent'、'AirToVent'、'AirAuto'、'AirOFF'")
            String mode) {
        return stateMachine.setAcAssistAirOutletMode(mode);
    }

    @Tool(name = "set_ac_assist_left_air_outlet", value = "控制副驾左侧出风口。当用户需要开启或关闭副驾左侧出风口时调用。")
    public String setAcAssistLeftAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcAssistLeftAirOutlet(auto);
    }

    @Tool(name = "set_ac_assist_right_air_outlet", value = "控制副驾右侧出风口。当用户需要开启或关闭副驾右侧出风口时调用。")
    public String setAcAssistRightAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        return stateMachine.setAcAssistRightAirOutlet(auto);
    }
}
