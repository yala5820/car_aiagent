package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextSection;
import com.hirain.aiagent.context.ContextSectionType;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车辆状态快照 Provider — HYBRID 模式不渲染，避免与 VehicleStatusPreProcessor 重复注入。
 */
public class VehicleStateContextProvider implements ContextProvider {

    @Override
    public String name() {
        return "VehicleStateContextProvider";
    }

    @Override
    public ContextSectionType type() {
        return ContextSectionType.VEHICLE_STATE;
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String snapshot = "";

        if (input.vehicleStatusProvider() != null) {
            try {
                snapshot = input.vehicleStatusProvider().getVehicleStatus();
                metadata.put("vehicle_snapshot_available", true);
            } catch (Exception e) {
                snapshot = "";
                metadata.put("vehicle_snapshot_available", false);
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return ContextProviderResult.fallback(name(),
                        new ContextSection(type(), name(), false, "", 0, false, metadata),
                        "vehicle_status_provider_exception: " + errorMsg);
            }
        } else {
            metadata.put("vehicle_snapshot_available", false);
            return ContextProviderResult.fallback(name(),
                    new ContextSection(type(), name(), false, "", 0, false, metadata),
                    "vehicle_status_provider_not_configured");
        }

        // HYBRID 模式：不渲染车辆状态，避免与 VehicleStatusPreProcessor 重复注入
        ContextSection section = new ContextSection(
                type(), name(), false, snapshot, snapshot.length(), false, metadata);
        return ContextProviderResult.success(name(), section);
    }
}
