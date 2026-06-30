package com.hirain.aiagent.tools.vehicle.ac;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleAcManager {

    private static final String KEY_AC_STATUS = "空调开启";
    private static final String KEY_DRIVER_TEMPERATURE = "主驾侧温度（摄氏度）";
    private static final String KEY_ASSIST_TEMPERATURE = "副驾侧温度（摄氏度）";
    private static final String KEY_FAN_INTENSITY = "空调风量挡位（1-7递增）";
    private static final String KEY_ECO_MODE = "空调经济模式开启";
    private static final String KEY_ANION_STATUS = "负离子开启";
    private static final String KEY_CLEAN_MODE = "空调干燥除味模式";
    private static final String KEY_CIRCULATION_MODE = "空调循环模式";
    private static final String KEY_DRIVE_AUTO_SWEEP = "主驾自动扫风开启";
    private static final String KEY_ASSIST_AUTO_SWEEP = "副驾自动扫风开启";
    private static final String KEY_DRIVE_LEFT_AIR_OUTLET = "主驾左侧出风口开关";
    private static final String KEY_DRIVE_RIGHT_AIR_OUTLET = "主驾右侧出风口开关";
    private static final String KEY_ASSIST_AIR_OUTLET_MODE = "副驾电动出风口模式";
    private static final String KEY_ASSIST_LEFT_AIR_OUTLET = "副驾左侧出风口开关";
    private static final String KEY_ASSIST_RIGHT_AIR_OUTLET = "副驾右侧出风口开关";

    private boolean acStatus;
    private int acDriveTemp;
    private int acAssistTemp;
    private int acFanIntensity;
    private boolean acEcoMode;
    private boolean acAnionStatus;
    private String acCleanMode;
    private String acCycMode;
    private boolean acDriveSweepAuto;
    private boolean acAssistSweepAuto;
    private boolean acDriveLeftAirOutlet;
    private boolean acDriveRightAirOutlet;
    private String acAssistAirOutletMode;
    private boolean acAssistLeftAirOutlet;
    private boolean acAssistRightAirOutlet;
    private boolean formalfunc = false;

    public VehicleAcManager() {
        this.acStatus = false;
        this.acDriveTemp = 26;
        this.acAssistTemp = 26;
        this.acFanIntensity = 1;
        this.acEcoMode = false;
        this.acAnionStatus = false;
        this.acCleanMode = "关闭";
        this.acCycMode = "自动";
        this.acDriveSweepAuto = false;
        this.acAssistSweepAuto = false;
        this.acDriveLeftAirOutlet = false;
        this.acDriveRightAirOutlet = false;
        this.acAssistAirOutletMode = "关闭";
        this.acAssistLeftAirOutlet = false;
        this.acAssistRightAirOutlet = false;
    }

    /** 查询空调状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getAcStatus() {
        SoaService.Companion.getInstance().getAcStatus();
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_AC_STATUS, acStatus);
            json.put(KEY_DRIVER_TEMPERATURE, acDriveTemp);
            json.put(KEY_ASSIST_TEMPERATURE, acAssistTemp);
            json.put(KEY_FAN_INTENSITY, acFanIntensity);
            json.put(KEY_ECO_MODE, acEcoMode);
            json.put(KEY_ANION_STATUS, acAnionStatus);
            json.put(KEY_CLEAN_MODE, acCleanMode);
            json.put(KEY_CIRCULATION_MODE, acCycMode);
            json.put(KEY_DRIVE_AUTO_SWEEP, acDriveSweepAuto);
            json.put(KEY_ASSIST_AUTO_SWEEP, acAssistSweepAuto);
            json.put(KEY_DRIVE_LEFT_AIR_OUTLET, acDriveLeftAirOutlet);
            json.put(KEY_DRIVE_RIGHT_AIR_OUTLET, acDriveRightAirOutlet);
            json.put(KEY_ASSIST_AIR_OUTLET_MODE, acAssistAirOutletMode);
            json.put(KEY_ASSIST_LEFT_AIR_OUTLET, acAssistLeftAirOutlet);
            json.put(KEY_ASSIST_RIGHT_AIR_OUTLET, acAssistRightAirOutlet);
        } catch (JSONException e) {
            return "获取空调系统状态失败。";
        }
        return json.toString();
    }

    // ── 空调开关 ──

    @Tool(name = "set_ac_status", value = "控制空调开启/关闭。当用户要求打开或关闭空调时调用。")
    public String setAcStatus(@P("true 开启，false 关闭") boolean status) {
        SoaService.Companion.getInstance().set_ac_status(status);
        if (formalfunc) this.acStatus = status;
        return "空调" + (status ? "开启" : "关闭") + "成功";
    }

    // ── 温度 ──

    @Tool(name = "set_ac_drive_temp", value = "调节主驾空调温度。当用户需要调整主驾侧温度时调用。")
    public String setAcDriveTemp(@P("温度（摄氏度），范围 16-31") int temp) {
        SoaService.Companion.getInstance().set_ac_drive_temp(temp);
        if (formalfunc) this.acDriveTemp = temp;
        return "主驾温度调节成功";
    }

    @Tool(name = "set_ac_assist_temp", value = "调节副驾空调温度。当用户需要调整副驾侧温度时调用。")
    public String setAcAssistTemp(@P("温度（摄氏度），范围 16-31") int temp) {
        SoaService.Companion.getInstance().set_ac_assist_temp(temp);
        if (formalfunc) this.acAssistTemp = temp;
        return "副驾温度调节成功";
    }

    // ── 风量 ──

    @Tool(name = "set_ac_fan_intensity", value = "调节空调风量挡位。当用户需要调整风量大小时调用。")
    public String setAcFanIntensity(@P("风量挡位，范围 1-7（递增）") int intensity) {
        SoaService.Companion.getInstance().set_ac_fan_intensity(intensity);
        if (formalfunc) this.acFanIntensity = intensity;
        return "风量挡位调节成功";
    }

    // ── 经济模式 ──

    @Tool(name = "set_ac_eco_mode", value = "控制空调经济模式。当用户需要开启或关闭经济模式时调用。")
    public String setAcEcoMode(@P("true 开启，false 关闭") boolean mode) {
        SoaService.Companion.getInstance().set_ac_eco_mode(mode);
        if (formalfunc) this.acEcoMode = mode;
        return "空调经济模式" + (mode ? "开启" : "关闭") + "成功";
    }

    // ── 负离子 ──

    @Tool(name = "set_ac_anion_status", value = "控制负离子功能。当用户需要开启或关闭负离子时调用。")
    public String setAcAnionStatus(@P("true 开启，false 关闭") boolean status) {
        SoaService.Companion.getInstance().set_ac_anion_status(status);
        if (formalfunc) this.acAnionStatus = status;
        return "负离子" + (status ? "开启" : "关闭") + "成功";
    }

    // ── 干燥除味 ──

    @Tool(name = "set_ac_clean_mode", value = "控制空调干燥除味模式。当用户需要清洁空调时调用。")
    public String setAcCleanMode(
            @P("模式，可选：'关闭'、'标准清洁'、'深度清洁'") String mode) {
        SoaService.Companion.getInstance().set_ac_clean_mode(mode);
        if (formalfunc) this.acCleanMode = mode;
        return "空调干燥除味模式成功设置为：" + mode;
    }

    // ── 内外循环 ──

    @Tool(name = "set_ac_cyc_mode", value = "控制空调内外循环模式。当用户需要切换循环方式时调用。")
    public String setAcCycMode(
            @P("模式，可选：'内循环'、'外循环'、'自动'") String mode) {
        SoaService.Companion.getInstance().set_ac_cyc_mode(mode);
        if (formalfunc) this.acCycMode = mode;
        return "空调内外循环模式成功设置为：" + mode;
    }

    // ── 自动扫风 ──

    @Tool(name = "set_ac_drive_sweep_auto", value = "控制主驾自动扫风功能。当用户需要开启或关闭主驾扫风时调用。")
    public String setAcDriveSweepAuto(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_drive_sweep_auto(auto);
        if (formalfunc) this.acDriveSweepAuto = auto;
        return "主驾自动扫风功能" + (auto ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_ac_assist_sweep_auto", value = "控制副驾自动扫风功能。当用户需要开启或关闭副驾扫风时调用。")
    public String setAcAssistSweepAuto(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_assist_sweep_auto(auto);
        if (formalfunc) this.acAssistSweepAuto = auto;
        return "副驾自动扫风功能" + (auto ? "开启" : "关闭") + "成功";
    }

    // ── 出风口 ──

    @Tool(name = "set_ac_drive_left_air_outlet", value = "控制主驾左侧出风口。当用户需要开启或关闭主驾左侧出风口时调用。")
    public String setAcDriveLeftAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_drive_left_air_outlet(auto);
        if (formalfunc) this.acDriveLeftAirOutlet = auto;
        return "主驾左侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_ac_drive_right_air_outlet", value = "控制主驾右侧出风口。当用户需要开启或关闭主驾右侧出风口时调用。")
    public String setAcDriveRightAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_drive_right_air_outlet(auto);
        if (formalfunc) this.acDriveRightAirOutlet = auto;
        return "主驾右侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_ac_assist_air_outlet_mode",
          value = "控制副驾电动出风口模式。当用户需要切换副驾出风方向时调用。")
    public String setAcAssistAirOutletMode(
            @P("模式，可选：'AirManual OFF'、'Air Vertical'、'Air Horizontal'、'Air Point'、'Mirror Wind'、'One way'、'AirnoVent'、'AirToVent'、'AirAuto'、'AirOFF'")
            String mode) {
        SoaService.Companion.getInstance().set_ac_assist_air_outlet_mode(mode);
        if (formalfunc) this.acAssistAirOutletMode = mode;
        return "副驾电动出风口模式：" + mode;
    }

    @Tool(name = "set_ac_assist_left_air_outlet", value = "控制副驾左侧出风口。当用户需要开启或关闭副驾左侧出风口时调用。")
    public String setAcAssistLeftAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_assist_left_air_outlet(auto);
        if (formalfunc) this.acAssistLeftAirOutlet = auto;
        return "副驾左侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_ac_assist_right_air_outlet", value = "控制副驾右侧出风口。当用户需要开启或关闭副驾右侧出风口时调用。")
    public String setAcAssistRightAirOutlet(@P("true 开启，false 关闭") boolean auto) {
        SoaService.Companion.getInstance().set_ac_assist_right_air_outlet(auto);
        if (formalfunc) this.acAssistRightAirOutlet = auto;
        return "副驾右侧出风口功能" + (auto ? "开启" : "关闭") + "成功";
    }
}
