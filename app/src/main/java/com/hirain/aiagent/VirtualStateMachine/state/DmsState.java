package com.hirain.aiagent.VirtualStateMachine.state;

public class DmsState {
    private String dmsDriveFatigue;
    private String dmsDriveDistractionLevel;
    private String dmsDriveEmotion;

    public DmsState() {
        this.dmsDriveFatigue = "清醒";
        this.dmsDriveDistractionLevel = "专注";
        this.dmsDriveEmotion = "中性";
    }

    public String getDmsDriveFatigue() { return dmsDriveFatigue; }
    public void setDmsDriveFatigue(String v) { this.dmsDriveFatigue = v; }
    public String getDmsDriveDistractionLevel() { return dmsDriveDistractionLevel; }
    public void setDmsDriveDistractionLevel(String v) { this.dmsDriveDistractionLevel = v; }
    public String getDmsDriveEmotion() { return dmsDriveEmotion; }
    public void setDmsDriveEmotion(String v) { this.dmsDriveEmotion = v; }
}
