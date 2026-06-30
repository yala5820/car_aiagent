package com.hirain.aiagent.core;

/**
 * 安全审查结果。
 * <p>
 * 由 {@link com.hirain.aiagent.core.component.SafetyGuard SafetyGuard} 返回，
 * 决定一个工具调用是被允许执行还是被否决。
 */
public final class SafetyVerdict {

    private static final SafetyVerdict ALLOW_INSTANCE = new SafetyVerdict(true, null);

    private final boolean allowed;
    private final String reason;

    private SafetyVerdict(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    /** 放行（单例） */
    public static SafetyVerdict allow() {
        return ALLOW_INSTANCE;
    }

    /** 否决 */
    public static SafetyVerdict veto(String reason) {
        return new SafetyVerdict(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public boolean isVetoed() {
        return !allowed;
    }

    /** 否决原因（仅 veto 时有意义） */
    public String reason() {
        return reason;
    }
}
