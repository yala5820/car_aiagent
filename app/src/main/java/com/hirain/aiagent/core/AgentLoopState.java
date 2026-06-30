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

    /** 尝试启动。若当前为 IDLE 则设为 RUNNING 返回 true；否则返回 false。 */
    public boolean tryStart() {
        boolean started = state.compareAndSet(State.IDLE.ordinal(), State.RUNNING.ordinal());
        if (started) {
            startTimeMs.set(System.currentTimeMillis());
            endTimeMs.set(0);
            currentIteration.set(0);
        }
        return started;
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
