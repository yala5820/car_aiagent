package com.hirain.aiagent.safety.rules;

import com.google.gson.JsonParser;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.SafetyCheckContext;
import com.hirain.aiagent.safety.SafetyDecision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DoorUnlockSafetyRuleTest {

    private final DoorUnlockSafetyRule rule = new DoorUnlockSafetyRule();

    @Test
    public void missingArgument_denies() {
        SafetyDecision decision = rule.check(context("{}"), new VehicleStateMachine());

        assertDenied(decision, SafetyDecision.ReasonCode.INVALID_ARGUMENT);
    }

    @Test
    public void nonBooleanArgument_denies() {
        SafetyDecision decision = rule.check(
                context("{\"arg0\":\"false\"}"), new VehicleStateMachine());

        assertDenied(decision, SafetyDecision.ReasonCode.INVALID_ARGUMENT);
    }

    @Test
    public void lockWhileMoving_allows() {
        VehicleStateMachine state = stateAtSpeed(70);

        SafetyDecision decision = rule.check(context("{\"arg0\":true}"), state);

        assertTrue(decision.isAllowed());
    }

    @Test
    public void unlockAtZeroSpeed_allows() {
        VehicleStateMachine state = stateAtSpeed(0);

        SafetyDecision decision = rule.check(context("{\"arg0\":false}"), state);

        assertTrue(decision.isAllowed());
    }

    @Test
    public void unlockAtOneKmh_denies() {
        VehicleStateMachine state = stateAtSpeed(1);

        SafetyDecision decision = rule.check(context("{\"arg0\":false}"), state);

        assertDenied(decision,
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED);
    }

    @Test
    public void unlockWhileMoving_denies() {
        VehicleStateMachine state = stateAtSpeed(70);

        SafetyDecision decision = rule.check(context("{\"arg0\":false}"), state);

        assertDenied(decision,
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED);
    }

    @Test
    public void unavailableSpeed_denies() {
        VehicleStateMachine state = new VehicleStateMachine() {
            @Override
            public int getVehicleSpd() {
                throw new IllegalStateException("speed unavailable");
            }
        };

        SafetyDecision decision = rule.check(context("{\"arg0\":false}"), state);

        assertDenied(decision, SafetyDecision.ReasonCode.SPEED_UNAVAILABLE);
    }

    private static VehicleStateMachine stateAtSpeed(int speed) {
        VehicleStateMachine state = new VehicleStateMachine();
        state.setVehicleSpd(speed);
        return state;
    }

    private static SafetyCheckContext context(String json) {
        return new SafetyCheckContext("set_door_lock",
                JsonParser.parseString(json).getAsJsonObject());
    }

    private static void assertDenied(SafetyDecision decision,
                                     SafetyDecision.ReasonCode code) {
        assertTrue(decision.isDenied());
        assertEquals(code, decision.reasonCode());
    }
}
