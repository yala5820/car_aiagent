package com.hirain.aiagent.tools.vehicle.dms;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleDMSManager {

    private final VehicleStateMachine stateMachine;

    public VehicleDMSManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询 DMS 状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getDmsStatus() {
        return stateMachine.getDmsStatus();
    }

    /**
     * 设置驾驶员疲劳等级。当监测到驾驶员疲劳状态变化时调用。
     */
    @Tool(name = "set_dms_drive_fatigue",
          value = "设置驾驶员疲劳等级。当监测到驾驶员疲劳状态变化时调用。")
    public String setDmsDriveFatigue(
            @P("等级，可选：'清醒'、'轻度'、'中度'、'重度'") String type) {
        return stateMachine.setDmsDriveFatigue(type);
    }

    /**
     * 设置驾驶员分心等级。当监测到驾驶员分心状态变化时调用。
     */
    @Tool(name = "set_dms_drive_distractionlevel",
          value = "设置驾驶员分心等级。当监测到驾驶员分心状态变化时调用。")
    public String setDmsDriveDistractionLevel(
            @P("等级，可选：'专注'、'轻度分心'、'中度分心'、'重度分心'") String type) {
        return stateMachine.setDmsDriveDistractionLevel(type);
    }

    /**
     * 设置驾驶员情绪。当监测到驾驶员情绪变化时调用。
     */
    @Tool(name = "set_dms_drive_emotion",
          value = "设置驾驶员情绪。当监测到驾驶员情绪状态变化时调用。")
    public String setDmsDriveEmotion(
            @P("情绪，可选：'中性'、'高兴'、'惊讶'、'悲伤'、'愤怒'、'厌恶'、'恐惧'")
            String type) {
        return stateMachine.setDmsDriveEmotion(type);
    }
}
