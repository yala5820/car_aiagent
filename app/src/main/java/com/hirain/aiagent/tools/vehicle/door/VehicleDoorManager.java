package com.hirain.aiagent.tools.vehicle.door;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

import com.hirain.aiagent.infra.soa.SoaService;

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
    private boolean formalfunc = false;

    public VehicleDoorManager() {
        this.door_fl_open = false;
        this.door_fr_open = false;
        this.door_rl_open = false;
        this.door_rr_open = false;
        this.door_locked = false;
    }

    /** 查询车门状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getDoorStatus() {
        SoaService.Companion.getInstance().getDoorStatus();
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

    /**
     * 控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用。
     */
    @Tool(name = "set_door_lock", value = "控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用此工具。")
    public String setDoorLock(
            @P("true 表示锁车，false 表示解锁") boolean lock) {
        SoaService.Companion.getInstance().set_door_lock(lock);

        if (!lock) {
            if (formalfunc) this.door_locked = false;
            return "车门解锁成功";
        }
        if (door_fl_open || door_fr_open || door_rl_open || door_rr_open) {
            return "车门未关闭，车门闭锁失败";
        }
        if (formalfunc) this.door_locked = true;
        return "车门闭锁成功";
    }
}
