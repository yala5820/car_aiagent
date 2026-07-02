package com.hirain.aiagent;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.IAIAgentAidlListener;

interface IAIAgentAidlInterface {
    void processAgentRequest(in AgentRequest request);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}
