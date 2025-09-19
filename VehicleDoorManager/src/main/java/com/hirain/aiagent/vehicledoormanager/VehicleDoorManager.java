package com.hirain.aiagent.vehicledoormanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;

public class VehicleDoorManager {
    private static final String KEY_DOOR_FL_STATUS = "左前门开启";
    private static final String KEY_DOOR_FR_STATUS = "右前门开启";
    private static final String KEY_DOOR_RL_STATUS = "左后门开启";
    private static final String KEY_DOOR_RR_STATUS = "右后门开启";
    private static final String KEY_DOOR_LOCKED = "车门闭锁";
    private final boolean door_fl_open;
    private final boolean door_fr_open;
    private final boolean door_rl_open;
    private final boolean door_rr_open;
    private boolean door_locked;
    public VehicleDoorManager() {
        this.door_fl_open = false;
        this.door_fr_open = false;
        this.door_rl_open = false;
        this.door_rr_open = false;
        this.door_locked = false;
    }
    public String getDoorStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_DOOR_FL_STATUS, door_fl_open);
            json.put(KEY_DOOR_FR_STATUS, door_fr_open);
            json.put(KEY_DOOR_RL_STATUS, door_rl_open);
            json.put(KEY_DOOR_RR_STATUS, door_rr_open);
            json.put(KEY_DOOR_LOCKED, door_locked);
        } catch (JSONException e) {
            return "获取车门状态失败。";
        }
        return json.toString();
    }
    @Tool("控制车门闭锁/解锁")
    public String set_door_lock(@P(value = "控制车门闭锁：true, 控制车门开锁：false") boolean lock) {
        if (!lock) {
            this.door_locked = false;
            return "车门解锁成功";
        }
        if (door_fl_open || door_fr_open || door_rl_open || door_rr_open) {
            return "车门未关闭，车门闭锁失败";
        }
        this.door_locked = true;
        return "车门闭锁成功";
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("set_door_lock");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            if (request.name().equals("set_door_lock")) {
                JSONObject json = new JSONObject(request.arguments());
                if (json.has("arg0")) {
                    return set_door_lock(json.getBoolean("arg0"));
                } else {
                    return "无效的工具参数。";
                }
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
