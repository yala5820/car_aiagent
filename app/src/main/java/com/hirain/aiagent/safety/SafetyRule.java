package com.hirain.aiagent.safety;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

/**
 * 单条确定性 Tool 安全规则。
 * <p>
 * 规则只允许读取车辆状态并返回审核结果，不能修改 VehicleStateMachine，
 * 车辆参数校验和状态收敛仍由真正的 Tool 执行阶段负责。
 */
@FunctionalInterface
public interface SafetyRule {

    SafetyDecision check(SafetyCheckContext context,
                         VehicleStateMachine vehicleStateMachine);
}
