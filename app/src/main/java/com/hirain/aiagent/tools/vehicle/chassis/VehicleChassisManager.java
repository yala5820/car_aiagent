package com.hirain.aiagent.tools.vehicle.chassis;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleChassisManager {

    private static final String KEY_CHASSIS_MODE = "底盘行驶模式";

    private String chassis_mode;
    private Boolean formalfunc = false;

    public VehicleChassisManager() {
        this.chassis_mode = "普通模式";
    }

    /** 查询底盘行驶模式（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getChassisStatus() {
        SoaService.Companion.getInstance().getChassisStatus();

        JSONObject json = new JSONObject();
        try {
            json.put(KEY_CHASSIS_MODE, "普通模式");
        } catch (JSONException e) {
            return "获取底盘系统状态失败。";
        }
        return json.toString();
    }

    /**
     * 控制底盘行驶模式，用于适应雪地、越野、普通驾驶场景。
     * 当用户需要切换驾驶模式时调用此工具。
     */
    @Tool(name = "set_chassis_mode",
          value = "控制底盘行驶模式。当用户需要切换驾驶模式（雪地/越野/普通）时调用。")
    public String setChassisMode(
            @P("模式，可选：'普通模式'、'越野模式'、'雪地模式'") String mode) {
        SoaService.Companion.getInstance().set_chassis_mode(mode);

        if (formalfunc) this.chassis_mode = mode;
        return "底盘行驶模式成功设置为：" + mode;
    }
}
