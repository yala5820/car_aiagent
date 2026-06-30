package com.hirain.aiagent.tools.vehicle.frag;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

public class VehicleFragManager {

    private static final String KEY_FRAG_TYPE = "车载香氛类型";
    private static final String KEY_FRAG_INTENSITY = "车载香氛浓度";

    private String fragType;
    private String fragIntensity;
    private boolean formalfunc = false;

    public VehicleFragManager() {
        this.fragType = "晨间松木";
        this.fragIntensity = "关闭";
    }

    /** 查询香氛状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getFragStatus() {
        SoaService.Companion.getInstance().getFragStatus();

        JSONObject json = new JSONObject();
        try {
            json.put(KEY_FRAG_TYPE, fragType);
            json.put(KEY_FRAG_INTENSITY, fragIntensity);
        } catch (JSONException e) {
            return "获取香氛系统状态失败。";
        }
        return json.toString();
    }

    /**
     * 控制车载香氛类型。当用户需要切换香氛气味时调用。
     */
    @Tool(name = "set_frag_type", value = "控制车载香氛类型。当用户需要切换香氛气味时调用。")
    public String setFragType(
            @P("类型，可选：'晨间松木'、'正午丁香'、'午夜橙香'") String type) {
        SoaService.Companion.getInstance().set_frag_type(type);

        if (formalfunc) this.fragType = type;
        return "车载香氛类型成功设置为：" + type;
    }

    /**
     * 控制车载香氛强度。当用户需要调节香氛浓度时调用。
     */
    @Tool(name = "set_frag_intensity", value = "控制车载香氛强度。当用户需要调节香氛浓度时调用。")
    public String setFragIntensity(
            @P("强度，可选：'关闭'、'低'、'中'、'高'") String intensity) {
        SoaService.Companion.getInstance().set_frag_intensity(intensity);

        if (formalfunc) this.fragIntensity = intensity;
        return "车载香氛强度成功设置为：" + intensity;
    }
}
