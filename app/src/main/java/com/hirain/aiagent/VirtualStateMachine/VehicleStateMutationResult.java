package com.hirain.aiagent.VirtualStateMachine;

/** 结构化状态写结果，避免 Eval 调用方依赖中文 Tool 返回文本判断成功与否。 */
public final class VehicleStateMutationResult {
    private final boolean success;
    private final String errorCode;
    private final String errorPath;
    private final String errorDetail;
    private final VehicleStateSnapshot snapshot;

    private VehicleStateMutationResult(boolean success, String errorCode, String errorPath,
                                       String errorDetail, VehicleStateSnapshot snapshot) {
        this.success = success;
        this.errorCode = errorCode;
        this.errorPath = errorPath;
        this.errorDetail = errorDetail;
        this.snapshot = snapshot;
    }

    public static VehicleStateMutationResult success(VehicleStateSnapshot snapshot) {
        return new VehicleStateMutationResult(true, null, null, null, snapshot);
    }
    public static VehicleStateMutationResult failure(String code, String path, String detail,
                                                     VehicleStateSnapshot snapshot) {
        return new VehicleStateMutationResult(false, code, path, detail, snapshot);
    }
    public boolean isSuccess() { return success; }
    public String getErrorCode() { return errorCode; }
    public String getErrorPath() { return errorPath; }
    public String getErrorDetail() { return errorDetail; }
    public VehicleStateSnapshot getSnapshot() { return snapshot; }
}
