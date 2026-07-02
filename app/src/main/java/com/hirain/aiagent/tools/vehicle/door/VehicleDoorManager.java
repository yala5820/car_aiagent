package com.hirain.aiagent.tools.vehicle.door;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

public class VehicleDoorManager {

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
    @Tool(name = "set_door_lock", value = "控制车门闭锁/解锁。当用户要求锁车、解锁车门时调用此工具。")
    public String setDoorLock(@P("true 表示锁车，false 表示解锁") boolean lock) {
        return stateMachine.setDoorLock(lock);
    }
}
