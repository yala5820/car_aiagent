package com.hirain.aiagent;

public interface IAIAgentServiceListener {
    void onAIAgentServiceConnected();
    void onAIAgentServiceDisconnected();
    void onAIResponse(AgentResponse response);
}
