package com.hirain.aiagent;

import com.hirain.aiagent.AgentResponse;

interface IAIAgentAidlListener {
    void onAIResponse(in AgentResponse response);
}
