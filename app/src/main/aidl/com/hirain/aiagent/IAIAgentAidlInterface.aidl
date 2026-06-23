package com.hirain.aiagent;

import com.hirain.aiagent.IAIAgentAidlListener;

interface IAIAgentAidlInterface {
    int requestAI(String arg);
    void sendMessage(String text);
    void sendMessageWithImage(String text, String imageBase64);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}
