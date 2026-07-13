package com.hirain.aiagent.VirtualStateMachine.state;

public class SpeedState {
    /**
     * 车速可能由状态采集线程更新、由 Agent 工作线程读取，因此使用 volatile 保证可见性。
     * 当前 Demo 不需要更复杂的状态同步机制，单个 int 读写已经具备原子性。
     */
    private volatile int vehicleSpd;

    public SpeedState() {
        // Demo 启动时按车辆静止处理，否则删除模型调速 Tool 后将不存在可到达的静止状态。
        this.vehicleSpd = 0;
    }

    public int getVehicleSpd() { return vehicleSpd; }
    public void setVehicleSpd(int v) { this.vehicleSpd = v; }
}
