package com.hirain.aiagent.context;

import com.hirain.aiagent.AgentRequest;
import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.runtime.RequestSession;
import com.hirain.aiagent.runtime.RequestSessionFactory;
import com.hirain.aiagent.runtime.RequestDeadline;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;

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
        return new RequestSessionFactory(() -> "generated", System::currentTimeMillis)
                .create(request, null,
                        IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                                List.of("空调"), text, "TEXT", "matched:VEHICLE_AC"),
                        ToolGroupSelectionResult.enriched(
                                ToolGroupRegistry.defaultRegistry(),
                                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                                "intent:VEHICLE_AC",
                                IntentConfidence.HIGH,
                                false));
    }

    /** CHAT_ONLY 会话，用于验证无工具场景。 */
    public static RequestSession chatOnlySession(String requestId, String sessionId,
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
        return new RequestSessionFactory(() -> "generated", System::currentTimeMillis)
                .create(request, null,
                        IntentResult.of(IntentTag.CHAT, IntentConfidence.HIGH,
                                List.of(), text, "TEXT", "CHAT_ONLY"),
                        ToolGroupSelectionResult.of(
                                List.of(),
                                List.of(),
                                "CHAT_ONLY",
                                IntentConfidence.HIGH,
                                false));
    }

    /** 需要澄清的会话，用于验证 Runtime 前后均不会暴露工具。 */
    public static RequestSession clarificationSession(String requestId, String sessionId,
                                                       String text) {
        return sessionWithSelection(requestId, sessionId, text,
                ToolGroupSelectionResult.clarificationRequired(
                        "clarification:test", IntentConfidence.LOW));
    }

    /** 工具选择失败关闭的会话，用于验证 Context 最后一层空工具防线。 */
    public static RequestSession failedClosedSession(String requestId, String sessionId,
                                                      String text) {
        return sessionWithSelection(requestId, sessionId, text,
                ToolGroupSelectionResult.failedClosed("selector:test_failure"));
    }

    private static RequestSession sessionWithSelection(String requestId, String sessionId,
                                                       String text,
                                                       ToolGroupSelectionResult selection) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(requestId);
        request.setSessionId(sessionId);
        request.setInputType("TEXT");
        request.setText(text);
        return new RequestSessionFactory(() -> "generated", System::currentTimeMillis)
                .create(request, null,
                        IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                                List.of(), text, "TEXT", "test"),
                        selection);
    }

    /** 已过期的 TEXT 会话，用于验证 AgentLoop 不会调用模型或 Tool。 */
    public static RequestSession expiredTextSession(String requestId, String sessionId,
                                                     String text) {
        AgentRequest request = new AgentRequest();
        request.setRequestId(requestId);
        request.setSessionId(sessionId);
        request.setUserId("user-expired");
        request.setPersonaId("chat");
        request.setInputType("TEXT");
        request.setText(text);
        long startedAt = System.currentTimeMillis() - 31_000L;
        return new RequestSessionFactory(() -> "generated", System::currentTimeMillis)
                .create(request, null,
                        IntentResult.of(IntentTag.CHAT, IntentConfidence.HIGH,
                                List.of(), text, "TEXT", "test_expired"),
                        ToolGroupSelectionResult.of(List.of(), List.of(), "CHAT_ONLY",
                                IntentConfidence.HIGH, false),
                        sessionId, new RequestDeadline(startedAt, 30_000L));
    }
}
