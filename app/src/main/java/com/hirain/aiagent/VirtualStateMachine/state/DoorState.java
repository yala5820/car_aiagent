package com.hirain.aiagent.VirtualStateMachine.state;

public class DoorState {
    private boolean doorFlOpen;
    private boolean doorFrOpen;
    private boolean doorRlOpen;
    private boolean doorRrOpen;
    private boolean doorLocked;

    public DoorState() {
        this.doorFlOpen = false;
        this.doorFrOpen = false;
        this.doorRlOpen = false;
        this.doorRrOpen = false;
        this.doorLocked = false;
    }

    public boolean isDoorFlOpen() { return doorFlOpen; }
    public void setDoorFlOpen(boolean v) { this.doorFlOpen = v; }
    public boolean isDoorFrOpen() { return doorFrOpen; }
    public void setDoorFrOpen(boolean v) { this.doorFrOpen = v; }
    public boolean isDoorRlOpen() { return doorRlOpen; }
    public void setDoorRlOpen(boolean v) { this.doorRlOpen = v; }
    public boolean isDoorRrOpen() { return doorRrOpen; }
    public void setDoorRrOpen(boolean v) { this.doorRrOpen = v; }
    public boolean isDoorLocked() { return doorLocked; }
    public void setDoorLocked(boolean v) { this.doorLocked = v; }

    public boolean isAnyDoorOpen() {
        return doorFlOpen || doorFrOpen || doorRlOpen || doorRrOpen;
    }
}
