package com.hirain.aiagent.VirtualStateMachine.state;

public class AcState {
    private boolean acStatus;
    private int acDriveTemp;
    private int acAssistTemp;
    private int acFanIntensity;
    private boolean acEcoMode;
    private boolean acAnionStatus;
    private String acCleanMode;
    private String acCycMode;
    private boolean acDriveSweepAuto;
    private boolean acAssistSweepAuto;
    private boolean acDriveLeftAirOutlet;
    private boolean acDriveRightAirOutlet;
    private String acAssistAirOutletMode;
    private boolean acAssistLeftAirOutlet;
    private boolean acAssistRightAirOutlet;

    public AcState() {
        this.acStatus = false;
        this.acDriveTemp = 26;
        this.acAssistTemp = 26;
        this.acFanIntensity = 1;
        this.acEcoMode = false;
        this.acAnionStatus = false;
        this.acCleanMode = "关闭";
        this.acCycMode = "自动";
        this.acDriveSweepAuto = false;
        this.acAssistSweepAuto = false;
        this.acDriveLeftAirOutlet = false;
        this.acDriveRightAirOutlet = false;
        this.acAssistAirOutletMode = "关闭";
        this.acAssistLeftAirOutlet = false;
        this.acAssistRightAirOutlet = false;
    }

    public boolean isAcStatus() { return acStatus; }
    public void setAcStatus(boolean v) { this.acStatus = v; }
    public int getAcDriveTemp() { return acDriveTemp; }
    public void setAcDriveTemp(int v) { this.acDriveTemp = v; }
    public int getAcAssistTemp() { return acAssistTemp; }
    public void setAcAssistTemp(int v) { this.acAssistTemp = v; }
    public int getAcFanIntensity() { return acFanIntensity; }
    public void setAcFanIntensity(int v) { this.acFanIntensity = v; }
    public boolean isAcEcoMode() { return acEcoMode; }
    public void setAcEcoMode(boolean v) { this.acEcoMode = v; }
    public boolean isAcAnionStatus() { return acAnionStatus; }
    public void setAcAnionStatus(boolean v) { this.acAnionStatus = v; }
    public String getAcCleanMode() { return acCleanMode; }
    public void setAcCleanMode(String v) { this.acCleanMode = v; }
    public String getAcCycMode() { return acCycMode; }
    public void setAcCycMode(String v) { this.acCycMode = v; }
    public boolean isAcDriveSweepAuto() { return acDriveSweepAuto; }
    public void setAcDriveSweepAuto(boolean v) { this.acDriveSweepAuto = v; }
    public boolean isAcAssistSweepAuto() { return acAssistSweepAuto; }
    public void setAcAssistSweepAuto(boolean v) { this.acAssistSweepAuto = v; }
    public boolean isAcDriveLeftAirOutlet() { return acDriveLeftAirOutlet; }
    public void setAcDriveLeftAirOutlet(boolean v) { this.acDriveLeftAirOutlet = v; }
    public boolean isAcDriveRightAirOutlet() { return acDriveRightAirOutlet; }
    public void setAcDriveRightAirOutlet(boolean v) { this.acDriveRightAirOutlet = v; }
    public String getAcAssistAirOutletMode() { return acAssistAirOutletMode; }
    public void setAcAssistAirOutletMode(String v) { this.acAssistAirOutletMode = v; }
    public boolean isAcAssistLeftAirOutlet() { return acAssistLeftAirOutlet; }
    public void setAcAssistLeftAirOutlet(boolean v) { this.acAssistLeftAirOutlet = v; }
    public boolean isAcAssistRightAirOutlet() { return acAssistRightAirOutlet; }
    public void setAcAssistRightAirOutlet(boolean v) { this.acAssistRightAirOutlet = v; }
}
