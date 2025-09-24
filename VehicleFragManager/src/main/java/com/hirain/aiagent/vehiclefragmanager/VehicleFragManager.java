package com.hirain.aiagent.vehiclefragmanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;
public class VehicleFragManager {
    private static final String KEY_FRAG_TYPE = "车载香氛类型";
    private static final String KEY_FRAG_INTENSITY = "车载香氛浓度";
    private String frag_type;
    private String frag_intensity;
    private boolean formalfunc = false;
    public VehicleFragManager() {
        this.frag_type = "晨间松木";
        this.frag_intensity = "关闭";
    }
    public String getFragStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_FRAG_TYPE, frag_type);
            json.put(KEY_FRAG_INTENSITY, frag_intensity);
        } catch (JSONException e) {
            return "获取香氛系统状态失败。";
        }
        return json.toString();
    }
    @Tool("控制车载香氛类型。")
    public String set_frag_type(@P(value = "类型，必须为：‘晨间松木’、‘正午丁香’、‘午夜橙香’中的一个。") String type) {
        if (formalfunc) this.frag_type = type;
        return "车载香氛类型成功设置为：" + type;
    }
    @Tool("控制车载香氛强度。")
    public String set_frag_intensity(@P(value = "强度，必须为：‘关闭’、‘低’、‘中’、‘高’中的一个。") String intensity) {
        if (formalfunc) this.frag_intensity = intensity;
        return "车载香氛强度成功设置为：" + intensity;
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("set_frag_type") || toolname.equals("set_frag_intensity");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("set_frag_type")) {
                return set_frag_type(json.getString("arg0"));
            } else if (request.name().equals("set_frag_intensity")) {
                return set_frag_intensity(json.getString("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
