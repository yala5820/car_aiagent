package com.hirain.aiagent.safety.rules;

import com.google.gson.JsonElement;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.SafetyCheckContext;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.SafetyRule;

/** 车门解锁规则：车辆只有在完全静止时才允许解锁，锁门不受车速限制。 */
public final class DoorUnlockSafetyRule implements SafetyRule {

    @Override
    public SafetyDecision check(SafetyCheckContext context,
                                VehicleStateMachine vehicleStateMachine) {
        JsonElement lockArg = context.arguments().get("arg0");
        if (lockArg == null || !lockArg.isJsonPrimitive()
                || !lockArg.getAsJsonPrimitive().isBoolean()) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                    "车门锁控制参数缺失或格式错误，已拒绝执行。"
            );
        }

        boolean lock = lockArg.getAsBoolean();
        if (lock) {
            return SafetyDecision.allow();
        }

        final int speedKmh;
        try {
            speedKmh = vehicleStateMachine.getVehicleSpd();
        } catch (Exception e) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.SPEED_UNAVAILABLE,
                    "无法获取当前车速，不能确认车辆是否静止，已拒绝解锁车门。"
            );
        }

        if (speedKmh == 0) {
            return SafetyDecision.allow();
        }
        return SafetyDecision.deny(
                SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED,
                "当前车速为 " + speedKmh + " km/h，车辆完全静止后才能解锁车门。"
        );
    }
}
