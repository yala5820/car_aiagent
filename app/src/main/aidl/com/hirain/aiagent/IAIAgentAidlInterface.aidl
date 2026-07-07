package com.hirain.aiagent;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.IAIAgentAidlListener;
import com.hirain.aiagent.ConversationRequest;
import com.hirain.aiagent.ConversationInfo;
import com.hirain.aiagent.ConversationListResponse;
import com.hirain.aiagent.ConversationOperationResult;
import com.hirain.aiagent.CancelRequestResult;

interface IAIAgentAidlInterface {
    void processAgentRequest(in AgentRequest request);
    ConversationOperationResult createConversation(in ConversationRequest request);
    ConversationListResponse listConversations(String userId);
    ConversationOperationResult deleteConversation(String userId, String sessionId);
    ConversationOperationResult switchConversation(String userId, String sessionId);
    ConversationInfo getActiveConversation(String userId);
    CancelRequestResult cancelAgentRequest(String requestId, String reason);
    void registerListener(IAIAgentAidlListener listener);
    void unregisterListener(IAIAgentAidlListener listener);
}
