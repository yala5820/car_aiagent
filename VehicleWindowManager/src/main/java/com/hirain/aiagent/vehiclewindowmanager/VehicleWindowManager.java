package com.hirain.aiagent.vehiclewindowmanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;

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
    private int window_fl_open;
    private int window_fr_open;
    private int window_rl_open;
    private int window_rr_open;
    private int sun_shadow_open;
    private int window_top_open;
    private boolean window_f_defrosting;
    private boolean window_r_heat;
    private boolean mirror_l_heat;
    private boolean mirror_r_heat;
    public VehicleWindowManager() {
        this.window_fl_open = 0;
        this.window_fr_open = 0;
        this.window_rl_open = 0;
        this.window_rr_open = 0;
        this.sun_shadow_open = 0;
        this.window_top_open = 0;
        this.window_f_defrosting = false;
        this.window_r_heat = false;
        this.mirror_l_heat = false;
        this.mirror_r_heat = false;
    }
    public String getWindowStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_WINDOW_FL_STATUS, window_fl_open);
            json.put(KEY_WINDOW_FR_STATUS, window_fr_open);
            json.put(KEY_WINDOW_RL_STATUS, window_rl_open);
            json.put(KEY_WINDOW_RR_STATUS, window_rr_open);
            json.put(KEY_TOP_WINDOW_STATUS, window_top_open);
            json.put(KEY_SUN_SHADOW_STATUS, sun_shadow_open);
            json.put(KEY_WINDOW_F_DEFROSTING, window_f_defrosting);
            json.put(KEY_WINDOW_R_HEAT, window_r_heat);
            json.put(KEY_MIRROR_L_HEAT, mirror_l_heat);
            json.put(KEY_MIRROR_R_HEAT, mirror_r_heat);
        } catch (JSONException e) {
            return "获取车窗、天窗、遮阳帘开度状态失败。";
        }
        return json.toString();
    }
    @Tool("调节左前车窗开度")
    public String setFlWindowStatus(@P(value = "开度百分比") int status) {
        this.window_fl_open = status;
        return "左前车窗控制成功";
    }
    @Tool("调节右前车窗开度")
    public String setFrWindowStatus(@P(value = "开度百分比") int status) {
        this.window_fr_open = status;
        return "右前车窗控制成功";
    }
    @Tool("调节左后车窗开度")
    public String setRlWindowStatus(@P(value = "开度百分比") int status) {
        this.window_rl_open = status;
        return "左后车窗控制成功";
    }
    @Tool("调节右后车窗开度")
    public String setRrWindowStatus(@P(value = "开度百分比") int status) {
        this.window_fl_open = status;
        return "右后车窗控制成功";
    }
    @Tool("调节天窗开度")
    public String setTopWindowStatus(@P(value = "开度百分比") int status) {
        this.window_top_open = status;
        return "天窗控制成功";
    }
    @Tool("调节遮阳帘开度")
    public String setSunShadowStatus(@P(value = "开度百分比") int status) {
        this.window_top_open = status;
        return "遮阳帘控制成功";
    }
    @Tool("控制前风挡除霜功能开启/关闭。")
    public String set_window_f_defrosting(@P(value = "开启：true, 关闭：false") boolean defrosting) {
        this.window_f_defrosting = defrosting;
        String tmp = defrosting ? "开启": "关闭";
        return "前风挡除霜" + tmp + "成功";
    }
    @Tool("控制后风挡加热功能开启/关闭。")
    public String set_window_r_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        this.window_r_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "后风挡加热" + tmp + "成功";
    }
    @Tool("控制左后视镜加热功能开启/关闭。")
    public String set_mirror_l_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        this.mirror_l_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "左后视镜加热" + tmp + "成功";
    }
    @Tool("控制右后视镜加热功能开启/关闭。")
    public String set_mirror_r_heat(@P(value = "开启：true, 关闭：false") boolean heat) {
        this.mirror_r_heat = heat;
        String tmp = heat ? "开启": "关闭";
        return "右后视镜加热" + tmp + "成功";
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("setFlWindowStatus") || toolname.equals("setFrWindowStatus")
            || toolname.equals("setRlWindowStatus") || toolname.equals("setRrWindowStatus")
            || toolname.equals("setTopWindowStatus") || toolname.equals("setSunShadowStatus")
            || toolname.equals("set_window_f_defrosting") || toolname.equals("set_window_r_heat")
            || toolname.equals("set_mirror_l_heat") || toolname.equals("set_mirror_r_heat");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("setFlWindowStatus")) {
                return setFlWindowStatus(json.getInt("arg0"));
            } else if (request.name().equals("setFrWindowStatus")) {
                return setFrWindowStatus(json.getInt("arg0"));
            } else if (request.name().equals("setRlWindowStatus")) {
                return setRlWindowStatus(json.getInt("arg0"));
            } else if (request.name().equals("setRrWindowStatus")) {
                return setRrWindowStatus(json.getInt("arg0"));
            } else if (request.name().equals("setTopWindowStatus")) {
                return setTopWindowStatus(json.getInt("arg0"));
            } else if (request.name().equals("setSunShadowStatus")) {
                return setSunShadowStatus(json.getInt("arg0"));
            } else if (request.name().equals("set_window_f_defrosting")) {
                return set_window_f_defrosting(json.getBoolean("arg0"));
            } else if (request.name().equals("set_window_r_heat")) {
                return set_window_r_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_mirror_l_heat")) {
                return set_mirror_l_heat(json.getBoolean("arg0"));
            } else if (request.name().equals("set_mirror_r_heat")) {
                return set_mirror_r_heat(json.getBoolean("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
