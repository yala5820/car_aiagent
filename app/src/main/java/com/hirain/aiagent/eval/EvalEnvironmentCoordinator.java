package com.hirain.aiagent.eval;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMutationResult;
import com.hirain.aiagent.VirtualStateMachine.VehicleStatePatch;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateSnapshot;
import com.hirain.aiagent.runtime.TimeProvider;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.BooleanSupplier;

/**
 * Debug Eval 的单环境租约与状态操作协调器。所有改变租约或 in-flight 计数的操作都在同一锁内完成，
 * 防止 TTL 到期时释放仍在执行 Tool 的环境。
 */
public final class EvalEnvironmentCoordinator {
    public static final long DEFAULT_TTL_MS = 120_000L;
    public static final long MIN_TTL_MS = 60_000L;
    public static final long MAX_TTL_MS = 600_000L;
    private final VehicleStateMachine stateMachine;
    private final TimeProvider timeProvider;
    private final BooleanSupplier environmentIdle;
    private final SecureRandom secureRandom = new SecureRandom();
    private int ownerUid = -1;
    private String token;
    private String correlationId;
    private long expiresAtMs;
    private int inFlight;
    private boolean shutdown;
    private boolean expiryPending;

    public EvalEnvironmentCoordinator(VehicleStateMachine stateMachine, TimeProvider timeProvider,
                                      BooleanSupplier environmentIdle) {
        this.stateMachine = stateMachine;
        this.timeProvider = timeProvider;
        this.environmentIdle = environmentIdle;
    }

    public synchronized OperationResult acquire(int uid, String correlationId, Long ttlMs) {
        if (shutdown) return fail("AGENT_NOT_READY");
        clearExpiredLocked();
        if (token != null) return fail("LEASE_ALREADY_HELD");
        if (!environmentIdle.getAsBoolean() || inFlight != 0) return fail("AGENT_BUSY");
        long ttl = ttlMs == null ? DEFAULT_TTL_MS : ttlMs;
        if (ttl < MIN_TTL_MS || ttl > MAX_TTL_MS) return fail("INVALID_REQUEST");
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        ownerUid = uid;
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        this.correlationId = correlationId;
        expiresAtMs = timeProvider.nowMillis() + ttl;
        return success(stateMachine.snapshot(timeProvider.nowMillis()), token, expiresAtMs);
    }

    public synchronized OperationResult reset(int uid, String token) {
        String auth = authorizeLocked(uid, token, true);
        if (auth != null) return fail(auth);
        if (!environmentIdle.getAsBoolean() || inFlight != 0) return fail("AGENT_BUSY");
        refreshLocked();
        return success(stateMachine.reset(timeProvider.nowMillis()).getSnapshot(), this.token, expiresAtMs);
    }

    public synchronized OperationResult apply(int uid, String token, VehicleStatePatch patch) {
        String auth = authorizeLocked(uid, token, true);
        if (auth != null) return fail(auth);
        if (!environmentIdle.getAsBoolean() || inFlight != 0) return fail("AGENT_BUSY");
        VehicleStateMutationResult result = stateMachine.applyPatch(patch, timeProvider.nowMillis());
        if (!result.isSuccess()) return new OperationResult(false, result.getErrorCode(), result.getErrorDetail(), null, 0L, result.getSnapshot());
        refreshLocked();
        return success(result.getSnapshot(), this.token, expiresAtMs);
    }

    public synchronized OperationResult read(int uid, String token) {
        String auth = authorizeLocked(uid, token, true);
        if (auth != null) return fail(auth);
        if (inFlight != 0) return fail("AGENT_BUSY");
        refreshLocked();
        return success(stateMachine.snapshot(timeProvider.nowMillis()), this.token, expiresAtMs);
    }

    public synchronized OperationResult release(int uid, String token) {
        String auth = authorizeLocked(uid, token, true);
        if (auth != null) return fail(auth);
        if (inFlight != 0) return fail("AGENT_BUSY");
        clearLeaseLocked();
        return success(stateMachine.snapshot(timeProvider.nowMillis()), null, 0L);
    }

    public synchronized EvalAdmissionDecision checkAdmission(int uid) {
        clearExpiredLocked();
        return token == null || ownerUid == uid ? EvalAdmissionDecision.allow() : EvalAdmissionDecision.busy();
    }

    public synchronized EvalRequestPermit beginRequest(int uid) {
        clearExpiredLocked();
        if (shutdown || token == null || ownerUid != uid) return null;
        inFlight++;
        refreshLocked();
        return new EvalRequestPermit(this::finishRequest);
    }

    public synchronized boolean isLeaseActive() { clearExpiredLocked(); return token != null; }
    public synchronized long environmentRevision() { return stateMachine.snapshot(timeProvider.nowMillis()).getEnvironmentRevision(); }
    public synchronized void shutdown() { shutdown = true; if (inFlight == 0) clearLeaseLocked(); else expiryPending = true; }

    private synchronized void finishRequest() {
        if (inFlight > 0) inFlight--;
        if (inFlight == 0 && (shutdown || expiryPending || (token != null && timeProvider.nowMillis() >= expiresAtMs))) clearLeaseLocked();
    }
    private String authorizeLocked(int uid, String supplied, boolean refresh) {
        if (shutdown) return "AGENT_NOT_READY";
        clearExpiredLocked();
        if (token == null) return "LEASE_NOT_FOUND";
        if (ownerUid != uid) return "LEASE_OWNER_MISMATCH";
        if (!token.equals(supplied)) return "LEASE_TOKEN_INVALID";
        if (refresh) refreshLocked();
        return null;
    }
    private void clearExpiredLocked() {
        if (token != null && timeProvider.nowMillis() >= expiresAtMs) {
            if (inFlight == 0) clearLeaseLocked(); else expiryPending = true;
        }
    }
    private void refreshLocked() { expiresAtMs = timeProvider.nowMillis() + DEFAULT_TTL_MS; }
    private void clearLeaseLocked() { ownerUid = -1; token = null; correlationId = null; expiresAtMs = 0L; expiryPending = false; }
    private OperationResult success(VehicleStateSnapshot snapshot, String token, long expiry) { return new OperationResult(true, "OK", null, token, expiry, snapshot); }
    private OperationResult fail(String code) { return new OperationResult(false, code, code, null, 0L, null); }

    public static final class OperationResult {
        private final boolean success; private final String errorCode; private final String errorDetail;
        private final String leaseToken; private final long leaseExpiresAtEpochMs; private final VehicleStateSnapshot snapshot;
        OperationResult(boolean success, String errorCode, String errorDetail, String leaseToken, long leaseExpiresAtEpochMs, VehicleStateSnapshot snapshot) {
            this.success = success; this.errorCode = errorCode; this.errorDetail = errorDetail; this.leaseToken = leaseToken; this.leaseExpiresAtEpochMs = leaseExpiresAtEpochMs; this.snapshot = snapshot;
        }
        public boolean isSuccess() { return success; } public String getErrorCode() { return errorCode; }
        public String getErrorDetail() { return errorDetail; } public String getLeaseToken() { return leaseToken; }
        public long getLeaseExpiresAtEpochMs() { return leaseExpiresAtEpochMs; } public VehicleStateSnapshot getSnapshot() { return snapshot; }
    }
}
