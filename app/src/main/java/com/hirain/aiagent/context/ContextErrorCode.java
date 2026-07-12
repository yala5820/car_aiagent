package com.hirain.aiagent.context;

/**
 * Context prepare/assemble 的领域错误码，供 AgentLoop/Runtime 稳定映射。
 */
public enum ContextErrorCode {
    REQUIRED_PROVIDER_FAILED,
    TOOL_SPEC_RESOLUTION_FAILED,
    MESSAGE_SEQUENCE_INVALID,
    CONTEXT_BUDGET_EXCEEDED,
    MEMORY_COMPACTION_FAILED,
    CONTEXT_CANCELLED,
    CONTEXT_INTERNAL_ERROR
}
