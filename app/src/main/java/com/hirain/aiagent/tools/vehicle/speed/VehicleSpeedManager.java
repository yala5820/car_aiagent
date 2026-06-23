package com.hirain.aiagent.tools.vehicle.speed;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;
public class VehicleSpeedManager {
    private static final String KEY_VEHICLE_SPD = "车速";
    private int vehicle_spd;
    public VehicleSpeedManager() {
        this.vehicle_spd = 70;
    }

    public String getSpeedStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_VEHICLE_SPD, vehicle_spd);
        } catch (JSONException e) {
            return "获取速度系统状态失败。";
        }
        return json.toString();
    }
    @Tool("车速大小调节。")
    public String set_vehicle_spd(@P(value = "整数，范围：0-240km/h,速度递增") int spd) {
        this.vehicle_spd = spd;
        return "车速大小调节成功";
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("vehicle_spd") ;
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("vehicle_spd")) {
                return set_vehicle_spd(json.getInt("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
