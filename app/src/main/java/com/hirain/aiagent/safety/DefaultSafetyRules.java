package com.hirain.aiagent.safety;

import com.hirain.aiagent.safety.rules.ChassisModeSafetyRule;
import com.hirain.aiagent.safety.rules.DoorUnlockSafetyRule;
import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo 阶段默认安全规则清单。
 * <p>
 * Tool 与规则的对应关系集中写在这里，便于后续扩充到十几条规则，
 * 同时避免让 AgentLoop、Service 或具体 Engine 混入业务映射逻辑。
 */
public final class DefaultSafetyRules {

    private DefaultSafetyRules() {
    }

    public static Map<String, List<SafetyRule>> create() {
        Map<String, List<SafetyRule>> rules = new LinkedHashMap<>();
        rules.put(VehicleDoorManager.TOOL_SET_DOOR_LOCK,
                List.of(new DoorUnlockSafetyRule()));
        rules.put(VehicleChassisManager.TOOL_SET_CHASSIS_MODE,
                List.of(new ChassisModeSafetyRule()));
        return Collections.unmodifiableMap(rules);
    }
}
