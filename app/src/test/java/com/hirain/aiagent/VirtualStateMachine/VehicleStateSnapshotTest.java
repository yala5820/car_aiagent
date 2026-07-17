package com.hirain.aiagent.VirtualStateMachine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/** 验证 Eval 快照与现有 Tool 状态机共享同一份状态，而不是创建第二状态机。 */
public class VehicleStateSnapshotTest {
    @Test public void snapshotUsesStableSchemaAndObservesToolMutation() {
        VehicleStateMachine machine = new VehicleStateMachine();
        assertEquals(0L, machine.snapshot(0L).getEnvironmentRevision());
        machine.setVehicleSpd(42);
        VehicleStateSnapshot snapshot = machine.snapshot(1_000L);
        assertEquals(1L, snapshot.getEnvironmentRevision());
        assertEquals(42, snapshot.getSystems().get("speed").get("vehicleSpd"));
        assertEquals("1970-01-01T00:00:01Z", snapshot.getCapturedAt());
        try { snapshot.getSystems().get("speed").put("vehicleSpd", 1); fail("snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
}
