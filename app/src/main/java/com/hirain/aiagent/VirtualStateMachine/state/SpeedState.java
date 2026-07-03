package com.hirain.aiagent.VirtualStateMachine.state;

public class SpeedState {
    private int vehicleSpd;

    public SpeedState() {
        this.vehicleSpd = 70;
    }

    public int getVehicleSpd() { return vehicleSpd; }
    public void setVehicleSpd(int v) { this.vehicleSpd = v; }
}
