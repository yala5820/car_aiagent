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

        if (result.success()) {
            response.setSuccess(true);
            response.setText(result.output());
            response.setErrorType(null);
        } else if ("TIMEOUT".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 请求超时");
            response.setErrorType("TIMEOUT");
        } else if ("EXCEPTION".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 请求失败 - " + (result.errorDetail() != null ? result.errorDetail() : "未知错误"));
            response.setErrorType("EXCEPTION");
        } else {
            response.setSuccess(false);
            response.setText(result.errorDetail() != null ? result.errorDetail() : "请求失败");
            response.setErrorType(result.errorType());
        }

        return response;
    }
}
