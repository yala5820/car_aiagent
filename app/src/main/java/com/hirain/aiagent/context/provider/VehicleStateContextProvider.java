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

    @Override public String sourceKey() { return com.hirain.aiagent.context.ContextPolicies.VEHICLE_STATE; }

    @Override
    public ContextLifecycle lifecycle() { return ContextLifecycle.ITERATION_DYNAMIC; }

    @Override
    public boolean required(RequestSession session, ContextBuildInput input) {
        return com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input).required();
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

        com.hirain.aiagent.context.ResolvedContextPolicy policy =
                com.hirain.aiagent.context.ContextPolicies.resolve(sourceKey(), session, input);

        TextContextContribution contribution = new TextContextContribution(
                policy, name(), TextContextContribution.TARGET_CONTEXT_DATA,
                snapshot, Map.of());

        if (status == ContextProviderStatus.FALLBACK) {
            return ContextProviderResult.fallback(name(), errorDetail, List.of(contribution));
        }
        return ContextProviderResult.success(name(), List.of(contribution));
    }
}
