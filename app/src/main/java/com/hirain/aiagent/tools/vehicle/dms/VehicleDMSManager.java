package com.hirain.aiagent.tools.vehicle.dms;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import org.json.JSONException;
import org.json.JSONObject;

public class VehicleDMSManager {

    private static final String KEY_DMS_DRIVE_FATIGUE = "驾驶员疲劳等级";
    private static final String KEY_DMS_DRIVE_DISTRACTIONLEVEL = "驾驶员分心等级";
    private static final String KEY_DMS_DRIVE_EMOTION = "驾驶员情绪";

    private String dmsDriveFatigue;
    private String dmsDriveDistractionLevel;
    private String dmsDriveEmotion;
    private boolean formalfunc = false;

    public VehicleDMSManager() {
        this.dmsDriveFatigue = "清醒";
        this.dmsDriveDistractionLevel = "清醒";
        this.dmsDriveEmotion = "中性";
    }

    /** 查询 DMS 状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getDmsStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_DMS_DRIVE_FATIGUE, dmsDriveFatigue);
            json.put(KEY_DMS_DRIVE_DISTRACTIONLEVEL, dmsDriveDistractionLevel);
            json.put(KEY_DMS_DRIVE_EMOTION, dmsDriveEmotion);
        } catch (JSONException e) {
            return "获取DMS系统状态失败。";
        }
        return json.toString();
    }

    /**
     * 设置驾驶员疲劳等级。当监测到驾驶员疲劳状态变化时调用。
     */
    @Tool(name = "set_dms_drive_fatigue",
          value = "设置驾驶员疲劳等级。当监测到驾驶员疲劳状态变化时调用。")
    public String setDmsDriveFatigue(
            @P("等级，可选：'清醒'、'轻度'、'中度'、'重度'") String type) {
        if (formalfunc) this.dmsDriveFatigue = type;
        return "驾驶员疲劳等级成功设置为：" + type;
    }

    /**
     * 设置驾驶员分心等级。当监测到驾驶员分心状态变化时调用。
     */
    @Tool(name = "set_dms_drive_distractionlevel",
          value = "设置驾驶员分心等级。当监测到驾驶员分心状态变化时调用。")
    public String setDmsDriveDistractionLevel(
            @P("等级，可选：'专注'、'轻度分心'、'中度分心'、'重度分心'") String type) {
        if (formalfunc) this.dmsDriveDistractionLevel = type;
        return "驾驶员分心等级成功设置为：" + type;
    }

    /**
     * 设置驾驶员情绪。当监测到驾驶员情绪变化时调用。
     */
    @Tool(name = "set_dms_drive_emotion",
          value = "设置驾驶员情绪。当监测到驾驶员情绪状态变化时调用。")
    public String setDmsDriveEmotion(
            @P("情绪，可选：'中性'、'高兴'、'惊讶'、'悲伤'、'愤怒'、'厌恶'、'恐惧'")
            String type) {
        if (formalfunc) this.dmsDriveEmotion = type;
        return "驾驶员情绪成功设置为：" + type;
    }
}
