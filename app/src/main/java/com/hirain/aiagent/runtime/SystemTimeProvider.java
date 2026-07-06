package com.hirain.aiagent.runtime;

public final class SystemTimeProvider implements TimeProvider {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
