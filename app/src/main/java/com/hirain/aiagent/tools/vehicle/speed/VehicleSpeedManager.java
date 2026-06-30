package com.hirain.aiagent.tools.vehicle.speed;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

public class VehicleSpeedManager {

    private static final String KEY_VEHICLE_SPD = "车速";

    private int vehicleSpd;

    public VehicleSpeedManager() {
        this.vehicleSpd = 70;
    }

    /** 查询当前车速（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getSpeedStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_VEHICLE_SPD, vehicleSpd);
        } catch (JSONException e) {
            return "获取速度系统状态失败。";
        }
        return json.toString();
    }

    /**
     * 调节车速大小。当用户需要设定巡航车速时调用。
     */
    @Tool(name = "set_vehicle_spd", value = "调节车速。当需要设定巡航车速时调用。")
    public String setVehicleSpd(
            @P("车速值，范围 0-240 km/h") int spd) {
        this.vehicleSpd = spd;
        return "车速大小调节成功";
    }
}
