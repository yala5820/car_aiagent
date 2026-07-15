package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextPoliciesTest {

    @Test
    public void allProductionSourcesAreRegisteredWithStableCorePolicy() {
        List<String> sources = List.of(
                ContextPolicies.RUNTIME, ContextPolicies.PERSONA, ContextPolicies.PROMPT,
                ContextPolicies.CURRENT_USER, ContextPolicies.INTENT, ContextPolicies.TOOL_GROUP,
                ContextPolicies.LONG_TERM_MEMORY, ContextPolicies.CALLER_EXTRA,
                ContextPolicies.SESSION_MEMORY_SUMMARY, ContextPolicies.SESSION_MEMORY,
                ContextPolicies.VEHICLE_STATE, ContextPolicies.TIME);
        for (String source : sources) {
            assertTrue(source, ContextPolicies.isRegistered(source));
            assertEquals(source, ContextPolicies.source(source).sourceKey());
        }
        assertFalse(ContextPolicies.source(ContextPolicies.PROMPT).trimEligible());
        assertFalse(ContextPolicies.source(ContextPolicies.SESSION_MEMORY).trimEligible());
        assertTrue(ContextPolicies.source(ContextPolicies.TIME).trimEligible());
        assertTrue(ContextPolicies.source(ContextPolicies.CALLER_EXTRA).trimEligible());
    }

    @Test
    public void chatOnlyHasOptionalEmptyToolsAndHiddenVehicle() {
        RequestSession session = TestRequestSessions.chatOnlySession(
                "req-chat", "conv-chat", "user-a", "chat", "client-chat", "你好");

        ResolvedContextPolicy tools = ContextPolicies.resolve(
                ContextPolicies.TOOL_GROUP, session, ContextBuildInput.builder().build());
        ResolvedContextPolicy vehicle = ContextPolicies.resolve(
                ContextPolicies.VEHICLE_STATE, session, ContextBuildInput.builder().build());

        assertFalse(tools.required());
        assertFalse(vehicle.required());
        assertEquals(ContextVisibility.POLICY_ONLY, vehicle.visibility());
    }

    @Test
    public void selectedVehicleRequestRequiresToolsAndVehicleContext() {
        RequestSession session = TestRequestSessions.textSession(
                "req-ac", "conv-ac", "user-a", "chat", "client-ac", "打开空调");

        ResolvedContextPolicy tools = ContextPolicies.resolve(
                ContextPolicies.TOOL_GROUP, session, ContextBuildInput.builder().build());
        ResolvedContextPolicy vehicle = ContextPolicies.resolve(
                ContextPolicies.VEHICLE_STATE, session, ContextBuildInput.builder().build());

        assertTrue(tools.required());
        assertTrue(vehicle.required());
        assertEquals(ContextVisibility.MODEL_VISIBLE, vehicle.visibility());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownProductionSourceFailsClosed() {
        ContextPolicies.resolve("unknown_source", null, ContextBuildInput.builder().build());
    }

    @Test
    public void summaryIsUntrustedRequiredContextDataPolicy() {
        ContextSourcePolicy summary = ContextPolicies.source(
                ContextPolicies.SESSION_MEMORY_SUMMARY);
        assertEquals(ContextTrustLevel.UNTRUSTED_DATA, summary.trustLevel());
        assertEquals(ContextVisibility.MODEL_VISIBLE, summary.visibility());
        assertEquals(ContextPriority.HIGH, summary.priority());
        assertTrue(summary.required());
        assertFalse(summary.trimEligible());
    }
}
