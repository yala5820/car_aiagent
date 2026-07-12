package com.hirain.aiagent.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的 Agent 循环状态跟踪器。
 * 供 Service 层（AIAgentService）观察当前 Agent 是否繁忙，用于并发控制。
 */
public class AgentLoopState {

    public enum State {
        IDLE, RUNNING, COMPLETED, ERROR, TIMEOUT
    }

    private final AtomicInteger state = new AtomicInteger(State.IDLE.ordinal());
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong endTimeMs = new AtomicLong(0);
    private final AtomicInteger currentIteration = new AtomicInteger(0);

    /**
     * 尝试启动。使用 CAS 保证原子性，允许从任何非 RUNNING 状态启动（IDLE / COMPLETED / ERROR / TIMEOUT）。
     * 两线程同时抢占时只能一个成功。
     */
    public boolean tryStart() {
        while (true) {
            int current = state.get();
            if (current == State.RUNNING.ordinal()) {
                return false;
            }
            if (state.compareAndSet(current, State.RUNNING.ordinal())) {
                startTimeMs.set(System.currentTimeMillis());
                endTimeMs.set(0);
                currentIteration.set(0);
                return true;
            }
            // CAS 失败 → 重试（另一线程改变了 state）
        }
    }

    public void markCompleted() {
        state.set(State.COMPLETED.ordinal());
        endTimeMs.set(System.currentTimeMillis());
    }

    public void markError() {
        state.set(State.ERROR.ordinal());
        endTimeMs.set(System.currentTimeMillis());
    }

    public void markTimeout() {
        state.set(State.TIMEOUT.ordinal());
        endTimeMs.set(System.currentTimeMillis());
    }

    public void reset() {
        state.set(State.IDLE.ordinal());
        startTimeMs.set(0);
        endTimeMs.set(0);
        currentIteration.set(0);
    }

    public State getState() {
        return State.values()[state.get()];
    }

    public boolean isBusy() {
        return state.get() == State.RUNNING.ordinal();
    }

    public int getCurrentIteration() {
        return currentIteration.get();
    }

    public void setIteration(int i) {
        currentIteration.set(i);
    }
}
