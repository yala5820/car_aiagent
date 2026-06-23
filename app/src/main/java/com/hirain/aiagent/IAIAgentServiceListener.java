package com.hirain.aiagent;

public interface IAIAgentServiceListener {
    void onAIAgentServiceConnected();
    void onAIAgentServiceDisconnected();
    void onAIResponse(int seqId, int captureMode, AIAgentData data);
}
