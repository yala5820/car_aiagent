package com.hirain.aiagent.eval;

import com.hirain.aiagent.VirtualStateMachine.VehicleStatePatch;

/** TestApp 到 AIAgent 的内部 Debug 请求，不是电脑端外层 EvalCommand。 */
public final class EvalDebugRequest {
    public String operation;
    public String correlationId;
    public String leaseToken;
    public Long ttlMs;
    public VehicleStatePatch statePatch;
    public int protocolMajor;
    public int protocolMinor;
    public String schemaHash;
}
