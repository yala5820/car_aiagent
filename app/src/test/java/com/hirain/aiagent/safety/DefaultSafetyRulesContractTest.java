package com.hirain.aiagent.safety;

import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 安全规则、真实 @Tool 名称和 LangChain4j 参数 schema 之间的契约测试。
 * 这些名称一旦不同步，高风险工具会因查不到规则而走默认放行，因此必须显式锁定。
 */
public class DefaultSafetyRulesContractTest {

    @Test
    public void defaultRules_matchRealToolNamesAndArg0Schema() {
        Map<String, List<SafetyRule>> rules = DefaultSafetyRules.create();
        assertEquals(DefaultSafetyRules.highRiskToolNames(), rules.keySet());
        assertEquals(List.of(VehicleDoorManager.TOOL_SET_DOOR_LOCK,
                        VehicleChassisManager.TOOL_SET_CHASSIS_MODE),
                List.copyOf(rules.keySet()));

        Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        collectSpecs(specs, VehicleDoorManager.class);
        collectSpecs(specs, VehicleChassisManager.class);

        for (Map.Entry<String, List<SafetyRule>> entry : rules.entrySet()) {
            ToolSpecification spec = specs.get(entry.getKey());
            assertNotNull("安全规则必须对应真实 @Tool: " + entry.getKey(), spec);
            assertTrue("安全规则当前按 arg0 读取，Tool schema 必须提供 arg0: "
                            + entry.getKey(),
                    spec.parameters() != null
                            && spec.parameters().properties().containsKey("arg0"));
            assertTrue("每个安全工具必须至少有一条规则",
                    entry.getValue() != null && !entry.getValue().isEmpty());
        }
    }

    private static void collectSpecs(Map<String, ToolSpecification> target,
                                     Class<?> managerClass) {
        for (ToolSpecification spec
                : ToolSpecifications.toolSpecificationsFrom(managerClass)) {
            target.put(spec.name(), spec);
        }
    }
}
