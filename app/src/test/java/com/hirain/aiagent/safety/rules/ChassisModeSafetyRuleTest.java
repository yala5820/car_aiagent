package com.hirain.aiagent.safety.rules;

import com.google.gson.JsonParser;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.SafetyCheckContext;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.SafetyCheckMode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChassisModeSafetyRuleTest {

    private final ChassisModeSafetyRule rule = new ChassisModeSafetyRule();

    @Test
    public void missingArgument_denies() {
        SafetyDecision decision = rule.check(context("{}"), new VehicleStateMachine());

        assertDenied(decision, SafetyDecision.ReasonCode.INVALID_ARGUMENT);
    }

    @Test
    public void nonStringArgument_denies() {
        SafetyDecision decision = rule.check(
                context("{\"arg0\":123}"), new VehicleStateMachine());

        assertDenied(decision, SafetyDecision.ReasonCode.INVALID_ARGUMENT);
    }

    @Test
    public void switchModeAtZeroSpeed_requiresConfirmation() {
        VehicleStateMachine state = stateAtSpeed(0);

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}"), state);

        assertTrue(decision.requiresConfirmation());
    }

    @Test
    public void switchModeWhileMoving_denies() {
        VehicleStateMachine state = stateAtSpeed(70);

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}"), state);

        assertDenied(decision,
                SafetyDecision.ReasonCode.CHASSIS_MODE_REQUIRES_STOPPED);
    }

    @Test
    public void invalidModeAtZeroSpeed_confirmationStillDefersEnumValidation() {
        VehicleStateMachine state = stateAtSpeed(0);

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"飞行模式\"}"), state);

        assertTrue(decision.requiresConfirmation());
        assertTrue(state.setChassisMode("飞行模式").contains("无效"));
    }

    @Test
    public void confirmedSwitchAtZeroSpeed_allows() {
        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}", SafetyCheckMode.CONFIRMED_RECHECK),
                stateAtSpeed(0));

        assertTrue(decision.isAllowed());
    }

    @Test
    public void confirmedSwitchAfterVehicleMoves_deniesStateChanged() {
        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}", SafetyCheckMode.CONFIRMED_RECHECK),
                stateAtSpeed(10));

        assertDenied(decision, SafetyDecision.ReasonCode.CONFIRMATION_STATE_CHANGED);
    }

    @Test
    public void unavailableSpeed_denies() {
        VehicleStateMachine state = new VehicleStateMachine() {
            @Override
            public int getVehicleSpd() {
                throw new IllegalStateException("speed unavailable");
            }
        };

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}"), state);

        assertDenied(decision, SafetyDecision.ReasonCode.SPEED_UNAVAILABLE);
    }

    private static VehicleStateMachine stateAtSpeed(int speed) {
        VehicleStateMachine state = new VehicleStateMachine();
        state.setVehicleSpd(speed);
        return state;
    }

    private static SafetyCheckContext context(String json) {
        return context(json, SafetyCheckMode.INITIAL);
    }

    private static SafetyCheckContext context(String json, SafetyCheckMode mode) {
        return new SafetyCheckContext("set_chassis_mode",
                JsonParser.parseString(json).getAsJsonObject(), mode);
    }

    private static void assertDenied(SafetyDecision decision,
                                     SafetyDecision.ReasonCode code) {
        assertTrue(decision.isDenied());
        assertEquals(code, decision.reasonCode());
    }
}
