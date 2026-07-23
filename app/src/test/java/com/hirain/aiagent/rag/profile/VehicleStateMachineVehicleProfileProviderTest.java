package com.hirain.aiagent.rag.profile;

import com.hirain.aiagent.VirtualStateMachine.VehicleStateMachine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Profile 读取只能来自状态机的专用只读接口，普通动态状态变更不应影响它。 */
public class VehicleStateMachineVehicleProfileProviderTest {
    @Test
    public void exposesImmutableModelYProfileFromStateMachine() {
        VehicleStateMachine stateMachine = new VehicleStateMachine();
        VehicleStateMachineVehicleProfileProvider provider = new VehicleStateMachineVehicleProfileProvider(stateMachine);
        stateMachine.setAcStatus(true);

        var profile = provider.currentProfile();
        assertEquals("MODEL_Y", profile.vehicleModel());
        assertEquals("2026", profile.modelYear());
        assertEquals("CN", profile.region());
        assertEquals("2026_REFRESH", profile.softwareVersion());
        assertEquals("RWD", profile.configurationCode());
        assertFalse(provider.isDemoProfile());
    }
}
