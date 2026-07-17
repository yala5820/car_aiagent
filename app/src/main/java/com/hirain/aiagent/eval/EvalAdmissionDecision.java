package com.hirain.aiagent.eval;

/** Eval 租约对主请求的确定性准入结论。 */
public final class EvalAdmissionDecision {
    public enum Status { ALLOW, DENY_BUSY }
    private final Status status;
    private final String reasonCode;
    private EvalAdmissionDecision(Status status, String reasonCode) { this.status = status; this.reasonCode = reasonCode; }
    public static EvalAdmissionDecision allow() { return new EvalAdmissionDecision(Status.ALLOW, "OK"); }
    public static EvalAdmissionDecision busy() { return new EvalAdmissionDecision(Status.DENY_BUSY, "EVAL_ENVIRONMENT_BUSY"); }
    public Status getStatus() { return status; }
    public String getReasonCode() { return reasonCode; }
    public boolean isAllowed() { return status == Status.ALLOW; }
}
