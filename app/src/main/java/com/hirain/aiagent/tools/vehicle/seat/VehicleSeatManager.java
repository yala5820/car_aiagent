package com.hirain.aiagent.tools.vehicle.seat;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleSeatManager {

    private static final String KEY_SEAT_FL_HEAT = "左前座椅加热开启";
    private static final String KEY_SEAT_FR_HEAT = "右前座椅加热开启";
    private static final String KEY_SEAT_RL_HEAT = "左后座椅加热开启";
    private static final String KEY_SEAT_RR_HEAT = "右后座椅加热开启";
    private static final String KEY_SEAT_FL_AIR = "左前座椅通风百分比";
    private static final String KEY_SEAT_FR_AIR = "右前座椅通风百分比";
    private static final String KEY_SEAT_RL_AIR = "左后座椅通风百分比";
    private static final String KEY_SEAT_RR_AIR = "右后座椅通风百分比";
    private static final String KEY_SEAT_DRIVE_MASSAGE_MODE = "主驾座椅按摩模式";
    private static final String KEY_SEAT_DRIVE_MASSAGE_INTENSITY = "主驾座椅按摩强度";
    private static final String KEY_STEERING_HEAT = "方向盘加热开启";

    private boolean formalfunc = false;
    private boolean seatFlHeat;
    private boolean seatFrHeat;
    private boolean seatRlHeat;
    private boolean seatRrHeat;
    private int seatFlAir;
    private int seatFrAir;
    private int seatRlAir;
    private int seatRrAir;
    private String seatMassageMode;
    private String seatMassageIntensity;
    private boolean steeringHeat;

    public VehicleSeatManager() {
        this.seatFlHeat = false;
        this.seatFrHeat = false;
        this.seatRlHeat = false;
        this.seatRrHeat = false;
        this.seatFlAir = 0;
        this.seatFrAir = 0;
        this.seatRlAir = 0;
        this.seatRrAir = 0;
        this.seatMassageMode = "波浪";
        this.seatMassageIntensity = "关闭";
        this.steeringHeat = false;
    }

    /** 查询座椅、方向盘状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getSeatStatus() {
        SoaService.Companion.getInstance().getSeatStatus();

        JSONObject json = new JSONObject();
        try {
            json.put(KEY_SEAT_FL_HEAT, seatFlHeat);
            json.put(KEY_SEAT_FR_HEAT, seatFrHeat);
            json.put(KEY_SEAT_RL_HEAT, seatRlHeat);
            json.put(KEY_SEAT_RR_HEAT, seatRrHeat);
            json.put(KEY_SEAT_FL_AIR, seatFlAir);
            json.put(KEY_SEAT_FR_AIR, seatFrAir);
            json.put(KEY_SEAT_RL_AIR, seatRlAir);
            json.put(KEY_SEAT_RR_AIR, seatRrAir);
            json.put(KEY_SEAT_DRIVE_MASSAGE_MODE, seatMassageMode);
            json.put(KEY_SEAT_DRIVE_MASSAGE_INTENSITY, seatMassageIntensity);
            json.put(KEY_STEERING_HEAT, steeringHeat);
        } catch (JSONException e) {
            return "获取座椅、方向盘状态失败。";
        }
        return json.toString();
    }

    // ── 座椅加热 ──

    @Tool(name = "set_seat_fl_heat", value = "控制左前座椅加热。当用户需要开启或关闭左前座椅加热时调用。")
    public String setSeatFlHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_seat_fl_heat(heat);
        if (formalfunc) this.seatFlHeat = heat;
        return "左前座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_seat_fr_heat", value = "控制右前座椅加热。当用户需要开启或关闭右前座椅加热时调用。")
    public String setSeatFrHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_seat_fr_heat(heat);
        if (formalfunc) this.seatFrHeat = heat;
        return "右前座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_seat_rl_heat", value = "控制左后座椅加热。当用户需要开启或关闭左后座椅加热时调用。")
    public String setSeatRlHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_seat_rl_heat(heat);
        if (formalfunc) this.seatRlHeat = heat;
        return "左后座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_seat_rr_heat", value = "控制右后座椅加热。当用户需要开启或关闭右后座椅加热时调用。")
    public String setSeatRrHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_seat_rr_heat(heat);
        if (formalfunc) this.seatRrHeat = heat;
        return "右后座椅加热" + (heat ? "开启" : "关闭") + "成功";
    }

    // ── 座椅通风 ──

    @Tool(name = "set_seat_fl_air", value = "控制左前座椅通风。当用户需要调节左前座椅通风时调用。")
    public String setSeatFlAir(@P("通风百分比，范围 0-100") int air) {
        SoaService.Companion.getInstance().set_seat_fl_air(air);
        if (formalfunc) this.seatFlAir = air;
        return "左前座椅通风控制成功";
    }

    @Tool(name = "set_seat_fr_air", value = "控制右前座椅通风。当用户需要调节右前座椅通风时调用。")
    public String setSeatFrAir(@P("通风百分比，范围 0-100") int air) {
        SoaService.Companion.getInstance().set_seat_fr_air(air);
        if (formalfunc) this.seatFrAir = air;
        return "右前座椅通风控制成功";
    }

    @Tool(name = "set_seat_rl_air", value = "控制左后座椅通风。当用户需要调节左后座椅通风时调用。")
    public String setSeatRlAir(@P("通风百分比，范围 0-100") int air) {
        SoaService.Companion.getInstance().set_seat_rl_air(air);
        if (formalfunc) this.seatRlAir = air;
        return "左后座椅通风控制成功";
    }

    @Tool(name = "set_seat_rr_air", value = "控制右后座椅通风。当用户需要调节右后座椅通风时调用。")
    public String setSeatRrAir(@P("通风百分比，范围 0-100") int air) {
        SoaService.Companion.getInstance().set_seat_rr_air(air);
        if (formalfunc) this.seatRrAir = air;
        return "右后座椅通风控制成功";
    }

    // ── 座椅按摩 ──

    @Tool(name = "set_seat_massage_mode",
          value = "控制主驾座椅按摩模式。当用户需要切换按摩模式时调用。")
    public String setSeatMassageMode(
            @P("模式，可选：'波浪'、'脉冲'、'揉捏'、'震动'、'腰部聚焦'") String mode) {
        SoaService.Companion.getInstance().set_seat_massage_mode(mode);
        if (formalfunc) this.seatMassageMode = mode;
        return "主驾座椅按摩模式成功设置为：" + mode;
    }

    @Tool(name = "set_seat_massage_intensity",
          value = "控制主驾座椅按摩强度。当用户需要调节按摩力度时调用。")
    public String setSeatMassageIntensity(
            @P("强度，可选：'关闭'、'弱'、'中等'、'强力'") String intensity) {
        SoaService.Companion.getInstance().set_seat_massage_intensity(intensity);
        if (formalfunc) this.seatMassageIntensity = intensity;
        return "主驾座椅按摩强度成功设置为：" + intensity;
    }

    // ── 方向盘 ──

    @Tool(name = "set_steering_heat", value = "控制方向盘加热。当用户需要开启或关闭方向盘加热时调用。")
    public String setSteeringHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_steering_heat(heat);
        if (formalfunc) this.steeringHeat = heat;
        return "方向盘加热" + (heat ? "开启" : "关闭") + "成功";
    }
}
