package com.hirain.aiagent.eval;

import android.content.Context;
import android.os.Binder;

/** 将固定 operation 映射到 coordinator，禁止 Debug 请求反射调用任意生产方法。 */
public final class EvalDebugBinder extends IAIAgentEvalDebug.Stub {
    private final EvalDebugProtocolCodec codec = new EvalDebugProtocolCodec();
    private final EvalDebugCallerValidator callerValidator;
    private final EvalVersionFingerprintProvider fingerprintProvider;
    public EvalDebugBinder(Context context) { callerValidator = new EvalDebugCallerValidator(context); fingerprintProvider = new EvalVersionFingerprintProvider(context); }
    @Override public String execute(String requestJson) {
        EvalDebugResponse response = new EvalDebugResponse();
        int uid = Binder.getCallingUid();
        if (!callerValidator.isAllowed(uid)) return codec.encode(fail(response, "CALLER_NOT_ALLOWED", null));
        EvalDebugProtocolCodec.DecodeResult decoded = codec.decode(requestJson);
        if (!decoded.isSuccess()) return codec.encode(fail(response, decoded.errorCode, null));
        EvalDebugRequest request = decoded.request;
        if ("GET_VERSION".equals(request.operation)) {
            java.util.Map<String, Object> fingerprint = new java.util.LinkedHashMap<>(fingerprintProvider.get());
            EvalEnvironmentRegistry.runtimeFingerprint().ifPresent(runtime -> {
                fingerprint.put("textModel", runtime.getTextModel());
                fingerprint.put("traceContentMode", runtime.getTraceContentMode());
            });
            response.success = true; response.status = "OK"; response.versionFingerprint = fingerprint; return codec.encode(response);
        }
        EvalEnvironmentCoordinator coordinator = EvalEnvironmentRegistry.current().orElse(null);
        if (coordinator == null) return codec.encode(fail(response, "AGENT_NOT_READY", null));
        EvalEnvironmentCoordinator.OperationResult result;
        switch (request.operation) {
            case "ACQUIRE_ENVIRONMENT": result = coordinator.acquire(uid, request.correlationId, request.ttlMs); break;
            case "RESET_STATE": result = coordinator.reset(uid, request.leaseToken); break;
            case "APPLY_STATE": result = coordinator.apply(uid, request.leaseToken, request.statePatch); break;
            case "READ_STATE": result = coordinator.read(uid, request.leaseToken); break;
            case "RELEASE_ENVIRONMENT": result = coordinator.release(uid, request.leaseToken); break;
            default: return codec.encode(fail(response, "UNKNOWN_OPERATION", null));
        }
        response.success = result.isSuccess(); response.status = result.isSuccess() ? "OK" : "ERROR"; response.errorCode = result.getErrorCode(); response.errorDetail = result.getErrorDetail();
        response.leaseToken = result.getLeaseToken(); response.leaseExpiresAtEpochMs = result.getLeaseExpiresAtEpochMs() == 0 ? null : result.getLeaseExpiresAtEpochMs(); response.snapshot = result.getSnapshot();
        return codec.encode(response);
    }
    private static EvalDebugResponse fail(EvalDebugResponse response, String code, String detail) { response.success = false; response.status = "ERROR"; response.errorCode = code; response.errorDetail = detail == null ? code : detail; return response; }
}
