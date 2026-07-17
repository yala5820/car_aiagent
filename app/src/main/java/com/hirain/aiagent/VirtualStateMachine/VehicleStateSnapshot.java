package com.hirain.aiagent.VirtualStateMachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eval 与状态读取共用的不可变车辆环境快照。
 * 设计原因：调用方只能消费复制后的结构化值，不能取得状态机内部 POJO 的可写引用。
 */
public final class VehicleStateSnapshot {
    private final String schemaVersion;
    private final long environmentRevision;
    private final String capturedAt;
    private final Map<String, Map<String, Object>> systems;

    public VehicleStateSnapshot(String schemaVersion, long environmentRevision, String capturedAt,
                                Map<String, Map<String, Object>> systems) {
        this.schemaVersion = schemaVersion;
        this.environmentRevision = environmentRevision;
        this.capturedAt = capturedAt;
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : systems.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        this.systems = Collections.unmodifiableMap(copy);
    }

    public String getSchemaVersion() { return schemaVersion; }
    public long getEnvironmentRevision() { return environmentRevision; }
    public String getCapturedAt() { return capturedAt; }
    public Map<String, Map<String, Object>> getSystems() { return systems; }
}
