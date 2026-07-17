package com.hirain.aiagent.eval;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 仅在同一 AIAgent 进程内连接主 Service 与 Debug Service 的 coordinator 注册表。 */
public final class EvalEnvironmentRegistry {
    private static final AtomicReference<EvalEnvironmentCoordinator> CURRENT = new AtomicReference<>();
    private static final AtomicReference<EvalRuntimeFingerprint> FINGERPRINT = new AtomicReference<>();
    private EvalEnvironmentRegistry() { }
    public static boolean install(EvalEnvironmentCoordinator coordinator) {
        return coordinator != null && CURRENT.compareAndSet(null, coordinator);
    }
    public static boolean install(EvalEnvironmentCoordinator coordinator, EvalRuntimeFingerprint fingerprint) {
        if (!install(coordinator)) return false;
        FINGERPRINT.set(fingerprint);
        return true;
    }
    public static boolean uninstall(EvalEnvironmentCoordinator coordinator) {
        boolean removed = coordinator != null && CURRENT.compareAndSet(coordinator, null);
        if (removed) FINGERPRINT.set(null);
        return removed;
    }
    public static Optional<EvalEnvironmentCoordinator> current() { return Optional.ofNullable(CURRENT.get()); }
    public static Optional<EvalRuntimeFingerprint> runtimeFingerprint() { return Optional.ofNullable(FINGERPRINT.get()); }
}
