package com.hirain.aiagent;

import com.hirain.aiagent.AIAgentData;

interface IAIAgentAidlListener {
    void onAIResponse(int seqId, int captureMode, in AIAgentData data);
}
