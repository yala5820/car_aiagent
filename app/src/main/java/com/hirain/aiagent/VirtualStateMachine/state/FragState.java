package com.hirain.aiagent.VirtualStateMachine.state;

public class FragState {
    private String fragType;
    private String fragIntensity;

    public FragState() {
        this.fragType = "晨间松木";
        this.fragIntensity = "关闭";
    }

    public String getFragType() { return fragType; }
    public void setFragType(String v) { this.fragType = v; }
    public String getFragIntensity() { return fragIntensity; }
    public void setFragIntensity(String v) { this.fragIntensity = v; }
}
