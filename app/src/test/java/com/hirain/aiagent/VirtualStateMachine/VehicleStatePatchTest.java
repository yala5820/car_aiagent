package com.hirain.aiagent.VirtualStateMachine;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Patch 必须先全量验证，避免最后一个字段非法时前面字段已写入。 */
public class VehicleStatePatchTest {
    @Test public void invalidMixedPatchDoesNotPartiallyMutate() {
        VehicleStateMachine machine = new VehicleStateMachine();
        Map<String, Object> speed = new LinkedHashMap<>(); speed.put("vehicleSpd", 50);
        Map<String, Object> ac = new LinkedHashMap<>(); ac.put("acDriveTemp", 99);
        Map<String, Map<String, Object>> systems = new LinkedHashMap<>(); systems.put("speed", speed); systems.put("ac", ac);
        VehicleStateMutationResult result = machine.applyPatch(new VehicleStatePatch(systems), 0L);
        assertFalse(result.isSuccess());
        assertEquals("VALUE_OUT_OF_RANGE", result.getErrorCode());
        assertEquals(0, machine.snapshot(1L).getSystems().get("speed").get("vehicleSpd"));
        assertEquals(0L, machine.snapshot(2L).getEnvironmentRevision());
    }

    @Test public void legalPatchChangesAllFieldsOnce() {
        VehicleStateMachine machine = new VehicleStateMachine();
        Map<String, Object> speed = new LinkedHashMap<>(); speed.put("vehicleSpd", 50);
        Map<String, Object> door = new LinkedHashMap<>(); door.put("doorLocked", false);
        Map<String, Map<String, Object>> systems = new LinkedHashMap<>(); systems.put("speed", speed); systems.put("door", door);
        VehicleStateMutationResult result = machine.applyPatch(new VehicleStatePatch(systems), 0L);
        assertTrue(result.isSuccess());
        assertEquals(1L, result.getSnapshot().getEnvironmentRevision());
        assertEquals(50, result.getSnapshot().getSystems().get("speed").get("vehicleSpd"));
    }
}
