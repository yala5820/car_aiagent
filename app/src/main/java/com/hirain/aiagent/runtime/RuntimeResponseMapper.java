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
        } else if ("BUSY".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText(result.errorDetail());
            response.setErrorType("BUSY");
            response.setStatus("BUSY");
            response.setErrorDetail(result.errorDetail());
        } else if ("DUPLICATE_REQUEST".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText(result.errorDetail());
            response.setErrorType("DUPLICATE_REQUEST");
            response.setStatus("DUPLICATE_REQUEST");
            response.setErrorDetail(result.errorDetail());
        } else if ("TOOL_SELECTION_FAILED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统暂时无法安全确定可用工具，请重新描述请求。");
            response.setErrorType("TOOL_SELECTION_FAILED");
            response.setStatus("TOOL_SELECTION_FAILED");
            response.setErrorDetail(result.errorDetail());
        } else if ("EXCEPTION".equals(result.errorType())) {
            response.setSuccess(false);
            // 异常详情可能包含 HTTP Body、文件路径或堆栈，只能留在受控日志/Trace。
            response.setText("系统: 请求暂时无法完成，请稍后重试。");
            response.setErrorType("EXCEPTION");
            response.setStatus("EXCEPTION");
            response.setErrorDetail(result.errorDetail());
        } else if ("INVALID_INPUT".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("INVALID_INPUT");
            response.setStatus("INVALID_INPUT");
            String detail = result.errorDetail() != null ? result.errorDetail() : "输入无效";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if ("CONTEXT_BUILD_FAILED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("CONTEXT_BUILD_FAILED");
            response.setStatus("CONTEXT_BUILD_FAILED");
            String detail = result.errorDetail() != null ? result.errorDetail() : "上下文构建失败";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if ("CONTEXT_BUDGET_EXCEEDED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("CONTEXT_BUDGET_EXCEEDED");
            response.setStatus("CONTEXT_BUDGET_EXCEEDED");
            response.setErrorDetail(result.errorDetail());
            response.setText("系统: 上下文超出预算限制");
        } else if ("REQUIRED_PROVIDER_FAILED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("REQUIRED_PROVIDER_FAILED");
            response.setStatus("REQUIRED_PROVIDER_FAILED");
            String detail = result.errorDetail() != null ? result.errorDetail() : "必需上下文来源失败";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if ("TOOL_SPEC_RESOLUTION_FAILED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("TOOL_SPEC_RESOLUTION_FAILED");
            response.setStatus("TOOL_SPEC_RESOLUTION_FAILED");
            String detail = result.errorDetail() != null ? result.errorDetail() : "工具规格无法解析";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if ("MESSAGE_SEQUENCE_INVALID".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("MESSAGE_SEQUENCE_INVALID");
            response.setStatus("MESSAGE_SEQUENCE_INVALID");
            String detail = result.errorDetail() != null ? result.errorDetail() : "消息序列非法";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if ("MEMORY_COMPACTION_FAILED".equals(result.errorType())) {
            response.setSuccess(false);
            response.setErrorType("MEMORY_COMPACTION_FAILED");
            response.setStatus("MEMORY_COMPACTION_FAILED");
            String detail = result.errorDetail() != null ? result.errorDetail() : "记忆压缩执行失败";
            response.setErrorDetail(detail);
            response.setText("系统: " + detail);
        } else if (isKnowledgeProtocolError(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 车辆知识回答校验未通过，请重新提问。");
            response.setErrorType(result.errorType());
            response.setStatus(result.errorType());
            response.setErrorDetail(result.errorDetail());
        } else if (isKnowledgeAvailabilityError(result.errorType())) {
            response.setSuccess(false);
            response.setText("系统: 当前无法获取适用的车辆资料。");
            response.setErrorType(result.errorType());
            response.setStatus(result.errorType());
            response.setErrorDetail(result.errorDetail());
        } else {
            response.setSuccess(false);
            response.setText("系统: 请求暂时无法完成，请稍后重试。");
            response.setErrorType(result.errorType());
            response.setStatus(result.errorType());
            response.setErrorDetail(result.errorDetail());
        }

        return response;
    }

    private static boolean isKnowledgeProtocolError(String code) {
        return "KNOWLEDGE_TOOL_NOT_CALLED".equals(code)
                || "TOOL_NOT_AUTHORIZED".equals(code)
                || "KNOWLEDGE_CITATION_INVALID".equals(code);
    }

    private static boolean isKnowledgeAvailabilityError(String code) {
        return "KNOWLEDGE_STORE_INITIALIZING".equals(code)
                || "KNOWLEDGE_STORE_UNAVAILABLE".equals(code)
                || "PROFILE_INCOMPLETE".equals(code)
                || "KNOWLEDGE_SCOPE_MISMATCH".equals(code)
                || "QUERY_EMPTY".equals(code)
                || "QUERY_TOO_LONG".equals(code)
                || "EMBEDDING_UNAVAILABLE".equals(code)
                || "RERANK_UNAVAILABLE".equals(code)
                || "BUNDLE_INVALID".equals(code);
    }
}
