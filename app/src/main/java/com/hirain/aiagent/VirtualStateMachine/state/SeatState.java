package com.hirain.aiagent.VirtualStateMachine.state;

public class SeatState {
    private boolean seatFlHeat;
    private boolean seatFrHeat;
    private boolean seatRlHeat;
    private boolean seatRrHeat;
    private int seatFlAir;
    private int seatFrAir;
    private int seatRlAir;
    private int seatRrAir;
    private String seatMassageMode;
    private String seatMassageIntensity;
    private boolean steeringHeat;

    public SeatState() {
        this.seatFlHeat = false;
        this.seatFrHeat = false;
        this.seatRlHeat = false;
        this.seatRrHeat = false;
        this.seatFlAir = 0;
        this.seatFrAir = 0;
        this.seatRlAir = 0;
        this.seatRrAir = 0;
        this.seatMassageMode = "波浪";
        this.seatMassageIntensity = "关闭";
        this.steeringHeat = false;
    }

    public boolean isSeatFlHeat() { return seatFlHeat; }
    public void setSeatFlHeat(boolean v) { this.seatFlHeat = v; }
    public boolean isSeatFrHeat() { return seatFrHeat; }
    public void setSeatFrHeat(boolean v) { this.seatFrHeat = v; }
    public boolean isSeatRlHeat() { return seatRlHeat; }
    public void setSeatRlHeat(boolean v) { this.seatRlHeat = v; }
    public boolean isSeatRrHeat() { return seatRrHeat; }
    public void setSeatRrHeat(boolean v) { this.seatRrHeat = v; }
    public int getSeatFlAir() { return seatFlAir; }
    public void setSeatFlAir(int v) { this.seatFlAir = v; }
    public int getSeatFrAir() { return seatFrAir; }
    public void setSeatFrAir(int v) { this.seatFrAir = v; }
    public int getSeatRlAir() { return seatRlAir; }
    public void setSeatRlAir(int v) { this.seatRlAir = v; }
    public int getSeatRrAir() { return seatRrAir; }
    public void setSeatRrAir(int v) { this.seatRrAir = v; }
    public String getSeatMassageMode() { return seatMassageMode; }
    public void setSeatMassageMode(String v) { this.seatMassageMode = v; }
    public String getSeatMassageIntensity() { return seatMassageIntensity; }
    public void setSeatMassageIntensity(String v) { this.seatMassageIntensity = v; }
    public boolean isSteeringHeat() { return steeringHeat; }
    public void setSteeringHeat(boolean v) { this.steeringHeat = v; }
}
