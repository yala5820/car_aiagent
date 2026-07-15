package com.hirain.aiagent.safety.confirmation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 单 Session、单槽位的内存 PendingAction Store。 */
public final class PendingToolActionStore {

    public enum TakeStatus {
        TAKEN,
        NOT_FOUND,
        SESSION_MISMATCH,
        EXPIRED,
        ALREADY_CONSUMED
    }

    public static final class TakeResult {
        private final TakeStatus status;
        private final PendingToolAction action;

        private TakeResult(TakeStatus status, PendingToolAction action) {
            this.status = status;
            this.action = action;
        }

        public TakeStatus status() { return status; }
        public PendingToolAction action() { return action; }
    }

    private final AtomicReference<PendingToolAction> slot = new AtomicReference<>();

    /** 新动作覆盖前先显式取消旧动作，避免旧授权残留。 */
    public void put(PendingToolAction action) {
        Objects.requireNonNull(action, "action");
        PendingToolAction previous = slot.getAndSet(action);
        if (previous != null) previous.transition(PendingToolAction.State.CANCELLED);
    }

    /** 原子领取动作；并发确认只有一个调用方能够取得原始 Tool。 */
    public TakeResult takeForConfirmation(String sessionId, long nowMs) {
        PendingToolAction action = slot.get();
        if (action == null) return new TakeResult(TakeStatus.NOT_FOUND, null);
        if (!sameSession(action.sessionId(), sessionId)) {
            return new TakeResult(TakeStatus.SESSION_MISMATCH, null);
        }
        if (nowMs >= action.expiresAtMs()) {
            if (slot.compareAndSet(action, null)) {
                action.transition(PendingToolAction.State.EXPIRED);
                return new TakeResult(TakeStatus.EXPIRED, action);
            }
            return new TakeResult(TakeStatus.ALREADY_CONSUMED, null);
        }
        if (!slot.compareAndSet(action, null)) {
            return new TakeResult(TakeStatus.ALREADY_CONSUMED, null);
        }
        if (!action.transition(PendingToolAction.State.CONSUMED)) {
            return new TakeResult(TakeStatus.ALREADY_CONSUMED, action);
        }
        return new TakeResult(TakeStatus.TAKEN, action);
    }

    public PendingToolAction cancel(String sessionId) {
        PendingToolAction action = slot.get();
        if (action == null || !sameSession(action.sessionId(), sessionId)) return null;
        if (!slot.compareAndSet(action, null)) return null;
        action.transition(PendingToolAction.State.CANCELLED);
        return action;
    }

    public void cancelAny() {
        PendingToolAction action = slot.getAndSet(null);
        if (action != null) action.transition(PendingToolAction.State.CANCELLED);
    }

    public void cancelByOriginalRequestId(String originalRequestId) {
        PendingToolAction action = slot.get();
        if (action == null || !Objects.equals(
                action.originalRequestId(), originalRequestId)) return;
        if (slot.compareAndSet(action, null)) {
            action.transition(PendingToolAction.State.CANCELLED);
        }
    }

    public PendingToolAction peek() {
        return slot.get();
    }

    private static boolean sameSession(String expected, String actual) {
        // 当前范围只有一个 Session；双方均为空仍视为同一默认会话。
        return Objects.equals(expected, actual);
    }
}
