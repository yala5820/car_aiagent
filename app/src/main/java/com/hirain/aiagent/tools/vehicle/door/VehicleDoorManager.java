package com.hirain.aiagent.tools.vehicle.door;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleDoorManager {

    /** Tool 名称同时供 Tool 声明和安全规则映射使用，避免字符串改名后安全规则静默失效。 */
    public static final String TOOL_SET_DOOR_LOCK = "set_door_lock";

    private final VehicleStateMachine stateMachine;

    public VehicleDoorManager(VehicleStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    /** 查询车门状态（非工具方法，供 AgentLoop 采集车辆状态） */
    public String getDoorStatus() {
        return stateMachine.getDoorStatus();
    }

    /**
     * 控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用。
     */
    @Tool(name = TOOL_SET_DOOR_LOCK, value = "控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用此工具。")
    public String setDoorLock(@P("true 表示锁车，false 表示解锁") boolean lock) {
        return stateMachine.setDoorLock(lock);
    }
}
