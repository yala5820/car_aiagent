package com.hirain.aiagent.safety;

import java.util.Objects;

/**
 * Tool 执行前安全审核结果。
 * <p>
 * 原因码供程序、测试和 Trace 稳定判断，中文原因只用于向 LLM 说明拒绝原因，
 * 避免业务逻辑依赖可能调整的自然语言文本。
 */
public final class SafetyDecision {

    public enum DecisionType {
        ALLOW,
        DENY
    }

    public enum ReasonCode {
        ALLOW,
        INVALID_ARGUMENT,
        SPEED_UNAVAILABLE,
        DOOR_UNLOCK_REQUIRES_STOPPED,
        CHASSIS_MODE_REQUIRES_STOPPED,
        RULE_EXECUTION_ERROR
    }

    private static final SafetyDecision ALLOW_INSTANCE =
            new SafetyDecision(DecisionType.ALLOW, ReasonCode.ALLOW, null);

    private final DecisionType type;
    private final ReasonCode reasonCode;
    private final String reason;

    private SafetyDecision(DecisionType type, ReasonCode reasonCode, String reason) {
        this.type = Objects.requireNonNull(type, "type");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.reason = reason;
    }

    /** 返回可复用的放行结果。 */
    public static SafetyDecision allow() {
        return ALLOW_INSTANCE;
    }

    /**
     * 创建拒绝结果。
     *
     * @param reasonCode 稳定拒绝原因码，不能使用 ALLOW
     * @param reason     提供给 LLM 的中文原因
     */
    public static SafetyDecision deny(ReasonCode reasonCode, String reason) {
        if (reasonCode == null || reasonCode == ReasonCode.ALLOW) {
            throw new IllegalArgumentException("DENY 必须使用非 ALLOW 原因码");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("DENY 必须包含拒绝原因");
        }
        return new SafetyDecision(DecisionType.DENY, reasonCode, reason);
    }

    public DecisionType type() {
        return type;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    public String reason() {
        return reason;
    }

    public boolean isAllowed() {
        return type == DecisionType.ALLOW;
    }

    public boolean isDenied() {
        return type == DecisionType.DENY;
    }
}
