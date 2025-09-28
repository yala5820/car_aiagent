package com.hirain.aiagent.vehiclechassismanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;
public class VehicleChassisManager {
    private static final String KEY_CHASSIS_MODE = "底盘行驶模式";
    private String chassis_mode;
    private Boolean formalfunc = false;
    public VehicleChassisManager() {
        this.chassis_mode = "普通模式";
    }
    public String getChassisStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_CHASSIS_MODE, "普通模式");
        } catch (JSONException e) {
            return "获取底盘系统状态失败。";
        }
        return json.toString();
    }
    @Tool("控制底盘行驶模式，用于适应雪地、越野、普通驾驶场景。")
    public String set_chassis_mode(@P(value = "模式，必须为：‘普通模式’、‘越野模式’、‘雪地模式’中的一个。") String mode) {
        if (formalfunc)this.chassis_mode = mode;
        return "底盘行驶模式成功设置为：" + mode;
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("set_chassis_mode");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("set_chassis_mode")) {
                return set_chassis_mode(json.getString("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
