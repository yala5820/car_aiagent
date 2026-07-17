package com.hirain.aiagent.eval;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateSnapshot;

/** 内部 Debug 响应；leaseToken 仅供 TestApp bridge 内存保存，绝不能写入电脑端结果。 */
public final class EvalDebugResponse {
    public ProtocolVersion protocolVersion = new ProtocolVersion();
    public boolean success;
    public String status;
    public String errorCode;
    public String errorDetail;
    public String leaseToken;
    public Long leaseExpiresAtEpochMs;
    public VehicleStateSnapshot snapshot;
    public Object versionFingerprint;

    public static final class ProtocolVersion {
        public int major = 1;
        public int minor = 0;
        public String schemaHash = "4cfb198980ae0f4074782100e3b1e02fb6eb8bc336b9aacd9709f54b98eb5fe2";
    }
}
