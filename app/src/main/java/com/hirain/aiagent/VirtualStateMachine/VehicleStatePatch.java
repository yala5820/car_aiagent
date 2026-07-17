package com.hirain.aiagent.VirtualStateMachine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eval 写状态的局部 Patch。Map 中出现字段即表示调用方显式给值，因而能区分 false/0 与未出现。
 */
public final class VehicleStatePatch {
    private final Map<String, Map<String, Object>> systems;

    public VehicleStatePatch(Map<String, Map<String, Object>> systems) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (systems != null) {
            for (Map.Entry<String, Map<String, Object>> entry : systems.entrySet()) {
                copy.put(entry.getKey(), entry.getValue() == null ? null : new LinkedHashMap<>(entry.getValue()));
            }
        }
        this.systems = Collections.unmodifiableMap(copy);
    }

    public Map<String, Map<String, Object>> getSystems() { return systems; }
    public boolean isEmpty() { return systems.isEmpty(); }
}
