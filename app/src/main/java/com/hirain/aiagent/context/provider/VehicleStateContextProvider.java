package com.hirain.aiagent.context.provider;

import com.hirain.aiagent.context.ContextBuildInput;
import com.hirain.aiagent.context.ContextLifecycle;
import com.hirain.aiagent.context.ContextPriority;
import com.hirain.aiagent.context.ContextProvider;
import com.hirain.aiagent.context.ContextProviderResult;
import com.hirain.aiagent.context.ContextProviderStatus;
import com.hirain.aiagent.context.ContextTrustLevel;
import com.hirain.aiagent.context.ContextVisibility;
import com.hirain.aiagent.context.TextContextContribution;
import com.hirain.aiagent.runtime.RequestSession;

import java.util.List;
import java.util.Map;

public class VehicleStateContextProvider implements ContextProvider {

    @Override
    public String name() { return "VehicleStateContextProvider"; }

    @Override
    public ContextLifecycle lifecycle() { return ContextLifecycle.ITERATION_DYNAMIC; }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        if (session == null || input == null || input.toolGroupRegistry() == null) return false;
        com.hirain.aiagent.toolgroup.ToolGroupSelectionResult sel = session.toolGroupSelectionResult();
        if (sel == null) return false;
        java.util.List<String> contextKeys = input.toolGroupRegistry()
                .requiredContextKeysFor(sel.selectedGroupIds());
        return contextKeys.contains("vehicle_status");
    }

    @Override
    public ContextProviderResult provide(RequestSession session, ContextBuildInput input) {
        String snapshot = "";
        ContextProviderStatus status = ContextProviderStatus.SUCCESS;
        String errorDetail = null;

        if (input.vehicleStatusProvider() != null) {
            try {
                snapshot = input.vehicleStatusProvider().getVehicleStatus();
            } catch (Exception e) {
                snapshot = "";
                status = ContextProviderStatus.FALLBACK;
                errorDetail = "vehicle_status_provider_exception: " + e.getMessage();
            }
        } else {
            status = ContextProviderStatus.FALLBACK;
            errorDetail = "vehicle_status_provider_not_configured";
        }

        boolean isRequired = required(session, input);
        ContextVisibility visibility = isRequired
                ? ContextVisibility.MODEL_VISIBLE : ContextVisibility.POLICY_ONLY;

        TextContextContribution contribution = new TextContextContribution(
                "vehicle_state", visibility, ContextTrustLevel.TRUSTED_DATA,
                ContextPriority.NORMAL, ContextLifecycle.ITERATION_DYNAMIC, false,
                name(), TextContextContribution.TARGET_CONTEXT_DATA,
                snapshot, Map.of());

        if (status == ContextProviderStatus.FALLBACK) {
            return ContextProviderResult.fallback(name(), errorDetail, List.of(contribution));
        }
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
