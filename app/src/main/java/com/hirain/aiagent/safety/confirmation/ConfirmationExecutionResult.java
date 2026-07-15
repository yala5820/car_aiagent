package com.hirain.aiagent.safety.confirmation;

import com.hirain.aiagent.safety.SafetyDecision;

/** 普通 TEXT 确认命令的确定性处理结果。 */
public final class ConfirmationExecutionResult {
    private final boolean handled;
    private final boolean success;
    private final String text;
    private final SafetyDecision.ReasonCode reasonCode;
    private final String confirmationId;

    private ConfirmationExecutionResult(boolean handled, boolean success, String text,
                                        SafetyDecision.ReasonCode reasonCode,
                                        String confirmationId) {
        this.handled = handled;
        this.success = success;
        this.text = text;
        this.reasonCode = reasonCode;
        this.confirmationId = confirmationId;
    }

    public static ConfirmationExecutionResult notHandled() {
        return new ConfirmationExecutionResult(false, false, null, null, null);
    }

    public static ConfirmationExecutionResult handled(boolean success, String text,
                                                       SafetyDecision.ReasonCode reasonCode,
                                                       String confirmationId) {
        return new ConfirmationExecutionResult(true, success, text, reasonCode, confirmationId);
    }

    public boolean handled() { return handled; }
    public boolean success() { return success; }
    public String text() { return text; }
    public SafetyDecision.ReasonCode reasonCode() { return reasonCode; }
    public String confirmationId() { return confirmationId; }
}
