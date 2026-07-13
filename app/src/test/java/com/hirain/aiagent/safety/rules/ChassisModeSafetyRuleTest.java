package com.hirain.aiagent.safety.rules;

import com.google.gson.JsonParser;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.SafetyCheckContext;
import com.hirain.aiagent.safety.SafetyDecision;

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
    public void switchModeAtZeroSpeed_allows() {
        VehicleStateMachine state = stateAtSpeed(0);

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"越野模式\"}"), state);

        assertTrue(decision.isAllowed());
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
    public void invalidModeAtZeroSpeed_safetyAllowsExecutionValidation() {
        VehicleStateMachine state = stateAtSpeed(0);

        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"飞行模式\"}"), state);

        assertTrue(decision.isAllowed());
        assertTrue(state.setChassisMode("飞行模式").contains("无效"));
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
        return new SafetyCheckContext("set_chassis_mode",
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static void assertDenied(SafetyDecision decision,
                                     SafetyDecision.ReasonCode code) {
        assertTrue(decision.isDenied());
        assertEquals(code, decision.reasonCode());
    }
}
