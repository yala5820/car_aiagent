package com.hirain.aiagent.VirtualStateMachine.state;

public class ChassisState {
    private String chassisMode;

    public ChassisState() {
        this.chassisMode = "普通模式";
    }

    public String getChassisMode() { return chassisMode; }
    public void setChassisMode(String v) { this.chassisMode = v; }
}
