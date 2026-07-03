package com.hirain.aiagent.VirtualStateMachine.state;

public class WindowState {
    private int windowFlOpen;
    private int windowFrOpen;
    private int windowRlOpen;
    private int windowRrOpen;
    private int windowTopOpen;
    private int sunShadowOpen;
    private boolean windowFDefrosting;
    private boolean windowRHeat;
    private boolean mirrorLHeat;
    private boolean mirrorRHeat;
    private boolean noWindowOpeningPassengers;

    public WindowState() {
        this.windowFlOpen = 0;
        this.windowFrOpen = 0;
        this.windowRlOpen = 0;
        this.windowRrOpen = 0;
        this.windowTopOpen = 0;
        this.sunShadowOpen = 0;
        this.windowFDefrosting = false;
        this.windowRHeat = false;
        this.mirrorLHeat = false;
        this.mirrorRHeat = false;
        this.noWindowOpeningPassengers = false;
    }

    public int getWindowFlOpen() { return windowFlOpen; }
    public void setWindowFlOpen(int v) { this.windowFlOpen = v; }
    public int getWindowFrOpen() { return windowFrOpen; }
    public void setWindowFrOpen(int v) { this.windowFrOpen = v; }
    public int getWindowRlOpen() { return windowRlOpen; }
    public void setWindowRlOpen(int v) { this.windowRlOpen = v; }
    public int getWindowRrOpen() { return windowRrOpen; }
    public void setWindowRrOpen(int v) { this.windowRrOpen = v; }
    public int getWindowTopOpen() { return windowTopOpen; }
    public void setWindowTopOpen(int v) { this.windowTopOpen = v; }
    public int getSunShadowOpen() { return sunShadowOpen; }
    public void setSunShadowOpen(int v) { this.sunShadowOpen = v; }
    public boolean isWindowFDefrosting() { return windowFDefrosting; }
    public void setWindowFDefrosting(boolean v) { this.windowFDefrosting = v; }
    public boolean isWindowRHeat() { return windowRHeat; }
    public void setWindowRHeat(boolean v) { this.windowRHeat = v; }
    public boolean isMirrorLHeat() { return mirrorLHeat; }
    public void setMirrorLHeat(boolean v) { this.mirrorLHeat = v; }
    public boolean isMirrorRHeat() { return mirrorRHeat; }
    public void setMirrorRHeat(boolean v) { this.mirrorRHeat = v; }
    public boolean isNoWindowOpeningPassengers() { return noWindowOpeningPassengers; }
    public void setNoWindowOpeningPassengers(boolean v) { this.noWindowOpeningPassengers = v; }
}
