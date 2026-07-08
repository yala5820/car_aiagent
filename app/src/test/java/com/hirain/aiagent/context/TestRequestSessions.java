package com.hirain.aiagent.context;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;

import java.util.List;

public final class TestRequestSessions {
    private TestRequestSessions() {}

    public static RequestSession textSession(String requestId, String sessionId,
                                             String userId, String personaId,
                                             String clientMessageId, String text) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(requestId);
        request.setSessionId(sessionId);
        request.setUserId(userId);
        request.setPersonaId(personaId);
        request.setClientMessageId(clientMessageId);
        request.setInputType("TEXT");
        request.setText(text);
        return new RequestSessionFactory(() -> "generated", () -> 1000L)
                .create(request, null,
                        IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                                List.of("空调"), text, "TEXT", "matched:VEHICLE_AC"),
                        ToolGroupSelectionResult.of(
                                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                                List.of("set_ac_status"),
                                "intent:VEHICLE_AC",
                                IntentConfidence.HIGH,
                                false));
    }
}
