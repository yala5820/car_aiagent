package com.hirain.aiagent.safety;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;

import org.junit.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 验证 Service 新建虚拟车辆后，安全规则的允许与拒绝状态都能实际到达。 */
public class VehicleSafetyBaselineTest {

    @Test
    public void newVehicle_startsStoppedAndRequiresConfirmationForHighRiskActions() {
        VehicleStateMachine state = new VehicleStateMachine();
        ToolSafetyEngine engine = new ToolSafetyEngine(
                state, DefaultSafetyRules.create());

        assertEquals(0, state.getVehicleSpd());
        assertTrue(engine.check(request(
                "set_door_lock", "{\"arg0\":false}")).requiresConfirmation());
        assertTrue(engine.check(request(
                "set_chassis_mode", "{\"arg0\":\"越野模式\"}")).requiresConfirmation());
        assertTrue(engine.recheckConfirmed(request(
                "set_door_lock", "{\"arg0\":false}")).isAllowed());
    }

    @Test
    public void movingVehicle_reachesBothDenyBranchesWithoutModelSpeedTool() {
        VehicleStateMachine state = new VehicleStateMachine();
        state.setVehicleSpd(70);
        ToolSafetyEngine engine = new ToolSafetyEngine(
                state, DefaultSafetyRules.create());

        assertEquals(SafetyDecision.ReasonCode.DOOR_UNLOCK_REQUIRES_STOPPED,
                engine.check(request("set_door_lock", "{\"arg0\":false}"))
                        .reasonCode());
        assertEquals(SafetyDecision.ReasonCode.CHASSIS_MODE_REQUIRES_STOPPED,
                engine.check(request("set_chassis_mode",
                        "{\"arg0\":\"越野模式\"}")).reasonCode());
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("baseline-request")
                .name(name)
                .arguments(arguments)
                .build();
    }
}
