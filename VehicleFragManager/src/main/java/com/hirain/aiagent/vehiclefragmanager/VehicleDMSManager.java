package com.hirain.aiagent.vehiclefragmanager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import org.json.JSONException;
import org.json.JSONObject;
public class VehicleDMSManager {
    private static final String KEY_DMS_DRVIE_FATIGUE = "驾驶员疲劳等级";
    private static final String KEY_DMS_DRIVE_DISTRACTIONLEVEL = "驾驶员分心等级";
    private static final String KEY_DMS_DRIVE_EMOTION = "驾驶员情绪";

    private String dms_drvie_fatigue;
    private String dms_drive_distractionlevel;
    private String dms_drive_emotion;
    public VehicleDMSManager() {
        this.dms_drvie_fatigue = "清醒";
        this.dms_drive_distractionlevel = "清醒";
        this.dms_drive_emotion = "中性";
    }

    public String getDmsStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_DMS_DRVIE_FATIGUE, dms_drvie_fatigue);
            json.put(KEY_DMS_DRIVE_DISTRACTIONLEVEL, dms_drive_distractionlevel);
            json.put(KEY_DMS_DRIVE_EMOTION, dms_drive_emotion);
        } catch (JSONException e) {
            return "获取DMS系统状态失败。";
        }
        return json.toString();
    }
    @Tool("驾驶员疲劳等级。")
    public String set_dms_drvie_fatigue(@P(value = "等级，必须为：'清醒', '轻度', '中度', '重度'中的一个。")  String type) {
        this.dms_drvie_fatigue = type;
        return "驾驶员疲劳等级成功设置为：" + type;
    }
    @Tool("驾驶员分心等级。")
    public String set_dms_drive_distractionlevel(@P(value = "等级，必须为：'专注', '轻度分心', '中度分心', '重度分心'中的一个。")  String type) {
        this.dms_drive_distractionlevel = type;
        return "驾驶员分心等级成功设置为：" + type;
    }
    @Tool("驾驶员情绪。")
    public String set_dms_drive_emotion(@P(value = "情绪，必须为：'中性', '高兴', '惊讶', '悲伤', '愤怒', '厌恶', '恐惧'中的一个。")  String type) {
        this.dms_drive_emotion = type;
        return "驾驶员情绪成功设置为：" + type;
    }
    public boolean hasTool(String toolname) {
        return toolname.equals("dms_drvie_fatigue") || toolname.equals("dms_drive_distractionlevel") || toolname.equals("dms_drive_emotion");
    }
    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("set_dms_drvie_fatigue")) {
                return set_dms_drvie_fatigue(json.getString("arg0"));
            } else if (request.name().equals("set_dms_drive_distractionlevel")) {
                return set_dms_drive_distractionlevel(json.getString("arg0"));
            } else if (request.name().equals("set_dms_drive_emotion")) {
                return set_dms_drive_emotion(json.getString("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }
}
