package com.hirain.aiagent.runtime;

import java.util.UUID;

public final class UuidIdGenerator implements IdGenerator {
    @Override
    public String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
