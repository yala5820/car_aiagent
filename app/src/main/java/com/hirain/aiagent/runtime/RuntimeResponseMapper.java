package com.hirain.aiagent.runtime;

import com.hirain.aiagent.AgentResponse;

/**
 * RuntimeResult → AgentResponse 转换器。
 * <p>
 * 设计原因：将 Agent 执行结果到 Parcelable AgentResponse 的映射逻辑
 * 集中在 Mapping 层，方便测试覆盖所有边界情况。
 */
public class RuntimeResponseMapper {

    public AgentResponse toAgentResponse(RuntimeResult result) {
        AgentResponse response = new AgentResponse();
        response.setRequestId(result.requestId());
        response.setSessionId(result.sessionId());
        response.setTimestamp(result.timestampMs());
        response.setUserId(result.userId());
        response.setPersonaId(result.personaId());
        response.setClientMessageId(result.clientMessageId());

        if (result.success()) {
            response.setSuccess(true);
            response.setText(result.output());
            response.setErrorType(null);
            response.setStatus("SUCCESS");
        } else if ("TIMEOUT".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 请求超时");
            response.setErrorType("TIMEOUT");
            response.setStatus("TIMEOUT");
            response.setErrorDetail(result.errorDetail());
        } else if ("CANCELLED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 请求已取消");
            response.setErrorType("CANCELLED");
            response.setStatus("CANCELLED");
            response.setErrorDetail(result.errorDetail());
        } else if ("EXCEPTION".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 请求失败 - " + (result.errorDetail() != null ? result.errorDetail() : "未知错误"));
            response.setErrorType("EXCEPTION");
            response.setStatus("EXCEPTION");
            response.setErrorDetail(result.errorDetail());
        } else {
            response.setSuccess(false);
            response.setText(result.errorDetail() != null ? result.errorDetail() : "请求失败");
            response.setErrorType(result.errorType());
            response.setStatus(result.errorType());
            response.setErrorDetail(result.errorDetail());
        }

        return response;
    }
}
