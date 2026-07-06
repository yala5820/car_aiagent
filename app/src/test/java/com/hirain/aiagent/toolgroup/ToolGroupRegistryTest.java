package com.hirain.aiagent.toolgroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.intentrouter.IntentConfidence;

import org.junit.Test;

import java.util.List;

public class ToolGroupRegistryTest {

    @Test
    public void toolGroupId_containsFirstVersionIds() {
        assertEquals(13, ToolGroupId.values().length);
        assertEquals(ToolGroupId.CHAT_ONLY_GROUP, ToolGroupId.valueOf("CHAT_ONLY_GROUP"));
        assertEquals(ToolGroupId.BASIC_STATUS_GROUP, ToolGroupId.valueOf("BASIC_STATUS_GROUP"));
        assertEquals(ToolGroupId.AC_GROUP, ToolGroupId.valueOf("AC_GROUP"));
        assertEquals(ToolGroupId.WINDOW_GROUP, ToolGroupId.valueOf("WINDOW_GROUP"));
        assertEquals(ToolGroupId.SEAT_GROUP, ToolGroupId.valueOf("SEAT_GROUP"));
        assertEquals(ToolGroupId.DOOR_GROUP, ToolGroupId.valueOf("DOOR_GROUP"));
        assertEquals(ToolGroupId.CHASSIS_GROUP, ToolGroupId.valueOf("CHASSIS_GROUP"));
        assertEquals(ToolGroupId.FRAGRANCE_GROUP, ToolGroupId.valueOf("FRAGRANCE_GROUP"));
        assertEquals(ToolGroupId.DMS_GROUP, ToolGroupId.valueOf("DMS_GROUP"));
        assertEquals(ToolGroupId.WEATHER_GROUP, ToolGroupId.valueOf("WEATHER_GROUP"));
        assertEquals(ToolGroupId.VISION_GROUP, ToolGroupId.valueOf("VISION_GROUP"));
        assertEquals(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.valueOf("COMMON_VEHICLE_GROUP"));
        assertEquals(ToolGroupId.ALL_SAFE_DEMO_GROUP, ToolGroupId.valueOf("ALL_SAFE_DEMO_GROUP"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void toolGroup_toolNamesAreImmutable() {
        ToolGroup group = new ToolGroup(ToolGroupId.AC_GROUP, "空调工具组", "空调控制",
                List.of("set_ac_status"), List.of("vehicle_status"), "MEDIUM", true);

        group.toolNames().add("another_tool");
    }

    @Test
    public void defaultRegistry_containsAcAndStatusGroups() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

        ToolGroup ac = registry.group(ToolGroupId.AC_GROUP);
        ToolGroup status = registry.group(ToolGroupId.BASIC_STATUS_GROUP);

        assertEquals(ToolGroupId.AC_GROUP, ac.groupId());
        assertTrue(ac.toolNames().contains("set_ac_status"));
        assertTrue(ac.toolNames().contains("set_ac_drive_temp"));
        assertEquals("MEDIUM", ac.riskLevel());
        assertTrue(ac.enabled());
        assertTrue(status.requiredContextKeys().contains("user_id"));
        assertTrue(status.requiredContextKeys().contains("vehicle_status"));
    }

    @Test
    public void defaultRegistry_canFindGroupsByToolName() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

        List<ToolGroup> groups = registry.groupsForToolName("set_ac_status");

        assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.AC_GROUP));
        assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.COMMON_VEHICLE_GROUP));
        assertTrue(groups.stream().anyMatch(group -> group.groupId() == ToolGroupId.ALL_SAFE_DEMO_GROUP));
    }

    @Test
    public void defaultRegistry_mergesToolNamesInGroupOrderAndRemovesDuplicates() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

        List<String> toolNames = registry.toolNamesFor(List.of(
                ToolGroupId.AC_GROUP,
                ToolGroupId.BASIC_STATUS_GROUP,
                ToolGroupId.AC_GROUP));

        assertEquals(15, toolNames.size());
        assertEquals("set_ac_status", toolNames.get(0));
        assertTrue(toolNames.contains("set_ac_assist_right_air_outlet"));
    }

    @Test
    public void defaultRegistry_allThirteenGroupsRegistered() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

        assertEquals(13, registry.allGroups().size());
        for (ToolGroupId id : ToolGroupId.values()) {
            assertNotNull("Missing group: " + id, registry.group(id));
        }
    }

    @Test
    public void aggregationGroups_haveRiskLevelEqualToMaxContainedRisk() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();

        // COMMON_VEHICLE_GROUP 含 DOOR_GROUP(HIGH) 和 CHASSIS_GROUP(HIGH) → 应为 HIGH
        assertEquals("HIGH", registry.group(ToolGroupId.COMMON_VEHICLE_GROUP).riskLevel());

        // ALL_SAFE_DEMO_GROUP 含全量车控 → 应为 HIGH
        assertEquals("HIGH", registry.group(ToolGroupId.ALL_SAFE_DEMO_GROUP).riskLevel());
    }

    @Test
    public void commonVehicleGroup_coversAllVehicleDomains() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        ToolGroup common = registry.group(ToolGroupId.COMMON_VEHICLE_GROUP);

        // 至少包含每个车控域的一个代表性 toolName
        assertTrue(common.toolNames().contains("set_ac_status"));           // AC
        assertTrue(common.toolNames().contains("set_fl_window_status"));   // Window
        assertTrue(common.toolNames().contains("set_seat_fl_heat"));       // Seat
        assertTrue(common.toolNames().contains("set_door_lock"));         // Door
        assertTrue(common.toolNames().contains("set_chassis_mode"));       // Chassis
        assertTrue(common.toolNames().contains("set_vehicle_spd"));        // Speed (in Chassis)
        assertTrue(common.toolNames().contains("set_frag_type"));          // Fragrance
        assertTrue(common.toolNames().contains("set_dms_drive_fatigue"));  // DMS
        // 不应包含非车控域
        assertFalse(common.toolNames().contains("getWeatherForecast"));
        assertFalse(common.toolNames().contains("front_camera_interaction"));
    }

    @Test
    public void toolGroupSelectionResult_fallbackReturnsChatOnlyGroup() {
        ToolGroupSelectionResult result = ToolGroupSelectionResult.fallback("test_reason");

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), result.selectedGroupIds());
        assertTrue(result.selectedToolNames().isEmpty());
        assertEquals("test_reason", result.selectionReason());
        assertEquals(IntentConfidence.NONE, result.confidence());
        assertTrue(result.fallbackUsed());
    }
}
