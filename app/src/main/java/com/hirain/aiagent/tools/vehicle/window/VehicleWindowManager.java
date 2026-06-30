package com.hirain.aiagent.tools.vehicle.window;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleWindowManager {

    private static final String KEY_WINDOW_FL_STATUS = "左前车窗开度百分比";
    private static final String KEY_WINDOW_FR_STATUS = "右前车窗开度百分比";
    private static final String KEY_WINDOW_RL_STATUS = "左后车窗开度百分比";
    private static final String KEY_WINDOW_RR_STATUS = "右后车窗开度百分比";
    private static final String KEY_TOP_WINDOW_STATUS = "天窗开度百分比";
    private static final String KEY_SUN_SHADOW_STATUS = "遮阳帘开度百分比";
    private static final String KEY_WINDOW_F_DEFROSTING = "前风挡除霜开启";
    private static final String KEY_WINDOW_R_HEAT = "后风挡加热开启";
    private static final String KEY_MIRROR_L_HEAT = "左后视镜加热开启";
    private static final String KEY_MIRROR_R_HEAT = "右后视镜加热开启";
    private static final String KEY_NO_WINDOW_OPENING_PASSENGERS = "乘员禁止开窗功能";

    private int windowFlOpen;
    private int windowFrOpen;
    private int windowRlOpen;
    private int windowRrOpen;
    private int sunShadowOpen;
    private int windowTopOpen;
    private boolean windowFDefrosting;
    private boolean windowRHeat;
    private boolean mirrorLHeat;
    private boolean mirrorRHeat;
    private boolean noWindowOpeningPassengers;
    private boolean formalfunc = false;

    public VehicleWindowManager() {
        this.windowFlOpen = 0;
        this.windowFrOpen = 0;
        this.windowRlOpen = 0;
        this.windowRrOpen = 0;
        this.sunShadowOpen = 0;
        this.windowTopOpen = 0;
        this.windowFDefrosting = false;
        this.windowRHeat = false;
        this.mirrorLHeat = false;
        this.mirrorRHeat = false;
        this.noWindowOpeningPassengers = false;
    }

    /** 查询车窗、天窗、遮阳帘状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getWindowStatus() {
        SoaService.Companion.getInstance().getWindowStatus();

        JSONObject json = new JSONObject();
        try {
            json.put(KEY_WINDOW_FL_STATUS, windowFlOpen);
            json.put(KEY_WINDOW_FR_STATUS, windowFrOpen);
            json.put(KEY_WINDOW_RL_STATUS, windowRlOpen);
            json.put(KEY_WINDOW_RR_STATUS, windowRrOpen);
            json.put(KEY_TOP_WINDOW_STATUS, windowTopOpen);
            json.put(KEY_SUN_SHADOW_STATUS, sunShadowOpen);
            json.put(KEY_WINDOW_F_DEFROSTING, windowFDefrosting);
            json.put(KEY_WINDOW_R_HEAT, windowRHeat);
            json.put(KEY_MIRROR_L_HEAT, mirrorLHeat);
            json.put(KEY_MIRROR_R_HEAT, mirrorRHeat);
            json.put(KEY_NO_WINDOW_OPENING_PASSENGERS, noWindowOpeningPassengers);
        } catch (JSONException e) {
            return "获取车窗、天窗、遮阳帘开度状态失败。";
        }
        return json.toString();
    }

    @Tool(name = "set_fl_window_status", value = "调节左前车窗开度。当用户需要升降左前车窗时调用。")
    public String setFlWindowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setFlWindowStatus(status);
        if (formalfunc) this.windowFlOpen = status;
        return "左前车窗控制成功";
    }

    @Tool(name = "set_fr_window_status", value = "调节右前车窗开度。当用户需要升降右前车窗时调用。")
    public String setFrWindowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setFrWindowStatus(status);
        if (formalfunc) this.windowFrOpen = status;
        return "右前车窗控制成功";
    }

    @Tool(name = "set_rl_window_status", value = "调节左后车窗开度。当用户需要升降左后车窗时调用。")
    public String setRlWindowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setRlWindowStatus(status);
        if (formalfunc) this.windowRlOpen = status;
        return "左后车窗控制成功";
    }

    @Tool(name = "set_rr_window_status", value = "调节右后车窗开度。当用户需要升降右后车窗时调用。")
    public String setRrWindowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setRrWindowStatus(status);
        if (formalfunc) this.windowRrOpen = status;
        return "右后车窗控制成功";
    }

    @Tool(name = "set_top_window_status", value = "调节天窗开度。当用户需要打开或关闭天窗时调用。")
    public String setTopWindowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setTopWindowStatus(status);
        if (formalfunc) this.windowTopOpen = status;
        return "天窗控制成功";
    }

    @Tool(name = "set_sun_shadow_status", value = "调节遮阳帘开度。当用户需要打开或关闭遮阳帘时调用。")
    public String setSunShadowStatus(@P("开度百分比，范围 0-100") int status) {
        SoaService.Companion.getInstance().setSunShadowStatus(status);
        if (formalfunc) this.sunShadowOpen = status;
        return "遮阳帘控制成功";
    }

    @Tool(name = "set_window_f_defrosting", value = "控制前风挡除霜功能。当用户需要开启或关闭前风挡除霜时调用。")
    public String setWindowFDefrosting(@P("true 开启，false 关闭") boolean defrosting) {
        SoaService.Companion.getInstance().set_window_f_defrosting(defrosting);
        if (formalfunc) this.windowFDefrosting = defrosting;
        return "前风挡除霜" + (defrosting ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_window_r_heat", value = "控制后风挡加热功能。当用户需要开启或关闭后风挡加热时调用。")
    public String setWindowRHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_window_r_heat(heat);
        if (formalfunc) this.windowRHeat = heat;
        return "后风挡加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_mirror_l_heat", value = "控制左后视镜加热功能。当用户需要开启或关闭左后视镜加热时调用。")
    public String setMirrorLHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_mirror_l_heat(heat);
        if (formalfunc) this.mirrorLHeat = heat;
        return "左后视镜加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_mirror_r_heat", value = "控制右后视镜加热功能。当用户需要开启或关闭右后视镜加热时调用。")
    public String setMirrorRHeat(@P("true 开启，false 关闭") boolean heat) {
        SoaService.Companion.getInstance().set_mirror_r_heat(heat);
        if (formalfunc) this.mirrorRHeat = heat;
        return "右后视镜加热" + (heat ? "开启" : "关闭") + "成功";
    }

    @Tool(name = "set_no_window_opening_passengers",
          value = "控制乘员禁止开窗功能。当用户需要禁止后排乘客开窗时调用。")
    public String setNoWindowOpeningPassengers(@P("true 开启禁止，false 关闭禁止") boolean open) {
        SoaService.Companion.getInstance().set_no_window_opening_passengers(open);
        if (formalfunc) this.noWindowOpeningPassengers = open;
        return "乘员禁止开窗功能" + (open ? "开启" : "关闭") + "成功";
    }
}
