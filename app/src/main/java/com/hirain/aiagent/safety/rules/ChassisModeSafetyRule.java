package com.hirain.aiagent.safety.rules;

import com.google.gson.JsonElement;
import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import com.hirain.aiagent.safety.SafetyCheckContext;
import com.hirain.aiagent.safety.SafetyDecision;
import com.hirain.aiagent.safety.SafetyRule;

/** 底盘模式规则：不区分座舱位置，车辆只有在完全静止时才允许切换。 */
public final class ChassisModeSafetyRule implements SafetyRule {

    @Override
    public SafetyDecision check(SafetyCheckContext context,
                                VehicleStateMachine vehicleStateMachine) {
        JsonElement modeArg = context.arguments().get("arg0");
        if (modeArg == null || !modeArg.isJsonPrimitive()
                || !modeArg.getAsJsonPrimitive().isString()) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.INVALID_ARGUMENT,
                    "底盘模式参数缺失或格式错误，已拒绝执行。"
            );
        }

        final int speedKmh;
        try {
            speedKmh = vehicleStateMachine.getVehicleSpd();
        } catch (Exception e) {
            return SafetyDecision.deny(
                    SafetyDecision.ReasonCode.SPEED_UNAVAILABLE,
                    "无法获取当前车速，不能确认车辆是否静止，已拒绝切换底盘模式。"
            );
        }

        if (speedKmh == 0) {
            return SafetyDecision.allow();
        }
        return SafetyDecision.deny(
                SafetyDecision.ReasonCode.CHASSIS_MODE_REQUIRES_STOPPED,
                "当前车速为 " + speedKmh + " km/h，车辆完全静止后才能切换底盘模式。"
        );
    }
}
