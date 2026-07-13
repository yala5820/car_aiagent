package com.hirain.aiagent.toolgroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.intentrouter.IntentConfidence;

import com.hirain.aiagent.tools.external.weather.WeatherUtils;
import com.hirain.aiagent.tools.vehicle.ac.VehicleAcManager;
import com.hirain.aiagent.tools.vehicle.chassis.VehicleChassisManager;
import com.hirain.aiagent.tools.vehicle.dms.VehicleDMSManager;
import com.hirain.aiagent.tools.vehicle.door.VehicleDoorManager;
import com.hirain.aiagent.tools.vehicle.frag.VehicleFragManager;
import com.hirain.aiagent.tools.vehicle.seat.VehicleSeatManager;
import com.hirain.aiagent.tools.vehicle.speed.VehicleSpeedManager;
import com.hirain.aiagent.tools.vehicle.window.VehicleWindowManager;
import com.hirain.aiagent.tools.vision.vl.VlManager;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

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
        assertFalse(common.toolNames().contains("set_vehicle_spd"));       // 车速仅作为 Demo 状态，不再暴露 Tool
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

    @Test
    public void toolGroupSelectionResult_ofBackfillsNewFields() {
        ToolGroupSelectionResult result = ToolGroupSelectionResult.of(
                List.of(ToolGroupId.AC_GROUP), List.of("set_ac_status"),
                "intent:AC", IntentConfidence.HIGH, false);

        assertTrue(result.requiredContextKeys().isEmpty());
        assertEquals("LOW", result.highestRiskLevel());
        assertFalse(result.allToolsFallback());
        assertFalse(result.containsAggregationGroup());
    }

    @Test
    public void toolGroupSelectionResult_fullFactorySetsAllFields() {
        ToolGroupSelectionResult result = ToolGroupSelectionResult.full(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                List.of("set_ac_status", "set_ac_drive_temp"),
                "intent:VEHICLE_AC",
                IntentConfidence.HIGH,
                false,
                List.of("user_id", "vehicle_status"),
                "MEDIUM",
                false,
                false);

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertEquals(List.of("set_ac_status", "set_ac_drive_temp"), result.selectedToolNames());
        assertEquals(List.of("user_id", "vehicle_status"), result.requiredContextKeys());
        assertEquals("MEDIUM", result.highestRiskLevel());
        assertFalse(result.allToolsFallback());
        assertFalse(result.containsAggregationGroup());
    }

    @Test
    public void toolGroupSelectionResult_allToolsFallbackFactory() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        ToolGroupSelectionResult result = ToolGroupSelectionResult.allToolsFallback(registry, "fallback_reason");

        assertEquals(List.of(ToolGroupId.ALL_SAFE_DEMO_GROUP), result.selectedGroupIds());
        assertEquals(registry.allToolNames(), result.selectedToolNames());
        assertEquals("fallback_reason", result.selectionReason());
        assertTrue(result.fallbackUsed());
        assertTrue(result.allToolsFallback());
        assertTrue(result.containsAggregationGroup());
        assertEquals("HIGH", result.highestRiskLevel());
    }

    @Test
    public void toolGroupSelectionResult_newFieldsAreImmutable() {
        ToolGroupSelectionResult result = ToolGroupSelectionResult.full(
                List.of(ToolGroupId.AC_GROUP), List.of("set_ac_status"),
                "test", IntentConfidence.HIGH, false,
                List.of("user_id"), "LOW", false, false);

        assertThrows(UnsupportedOperationException.class,
                () -> result.requiredContextKeys().add("new_key"));
    }

    // ── Phase 2: Registry 查询接口 ──

    @Test
    public void allToolNames_returnsAllUniqueToolNames() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        List<String> all = registry.allToolNames();

        assertFalse(all.isEmpty());
        assertTrue(all.contains("set_ac_status"));
        assertTrue(all.contains("set_fl_window_status"));
        assertTrue(all.contains("getWeatherForecast"));
        assertTrue(all.contains("front_camera_interaction"));
        assertFalse(all.contains("set_vehicle_spd"));
        // 纯聊天和基础状态组应无工具
        assertFalse(all.contains(""));
    }

    @Test
    public void allToolNames_isImmutable() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertThrows(UnsupportedOperationException.class,
                () -> registry.allToolNames().add("new_tool"));
    }

    @Test
    public void requiredContextKeysFor_acGroupMergesBasicStatusKeys() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        List<String> keys = registry.requiredContextKeysFor(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP));

        assertTrue(keys.contains("user_id"));
        assertTrue(keys.contains("vehicle_status"));
        assertEquals(2, keys.size());
    }

    @Test
    public void requiredContextKeysFor_chatOnlyGroupReturnsEmpty() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        List<String> keys = registry.requiredContextKeysFor(
                List.of(ToolGroupId.CHAT_ONLY_GROUP));

        assertTrue(keys.isEmpty());
    }

    @Test
    public void requiredContextKeysFor_weatherGroupReturnsEmpty() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        List<String> keys = registry.requiredContextKeysFor(
                List.of(ToolGroupId.WEATHER_GROUP));

        assertTrue(keys.isEmpty());
    }

    @Test
    public void highestRiskLevelFor_returnsHighest() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertEquals("HIGH", registry.highestRiskLevelFor(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.DOOR_GROUP)));
        assertEquals("MEDIUM", registry.highestRiskLevelFor(
                List.of(ToolGroupId.AC_GROUP, ToolGroupId.WEATHER_GROUP)));
    }

    @Test
    public void highestRiskLevelFor_emptyReturnsLow() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertEquals("LOW", registry.highestRiskLevelFor(List.of()));
    }

    @Test
    public void containsAggregationGroup_withCommonVehicleReturnsTrue() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertTrue(registry.containsAggregationGroup(
                List.of(ToolGroupId.COMMON_VEHICLE_GROUP)));
        assertTrue(registry.containsAggregationGroup(
                List.of(ToolGroupId.ALL_SAFE_DEMO_GROUP)));
    }

    @Test
    public void containsAggregationGroup_acOnlyReturnsFalse() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertFalse(registry.containsAggregationGroup(
                List.of(ToolGroupId.AC_GROUP)));
    }

    @Test
    public void isContextMarkerGroup_basicStatusReturnsTrue() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertTrue(registry.isContextMarkerGroup(ToolGroupId.BASIC_STATUS_GROUP));
    }

    @Test
    public void isContextMarkerGroup_acGroupReturnsFalse() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertFalse(registry.isContextMarkerGroup(ToolGroupId.AC_GROUP));
        assertFalse(registry.isContextMarkerGroup(ToolGroupId.CHAT_ONLY_GROUP));
    }

    @Test
    public void isAggregationGroup_commonVehicleReturnsTrue() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertTrue(registry.isAggregationGroup(ToolGroupId.COMMON_VEHICLE_GROUP));
        assertTrue(registry.isAggregationGroup(ToolGroupId.ALL_SAFE_DEMO_GROUP));
    }

    @Test
    public void isAggregationGroup_nullReturnsFalse() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        assertFalse(registry.isAggregationGroup(null));
    }

    // ── Phase 2: ValidationResult ──

    @Test
    public void validationResult_createValidWhenEmpty() {
        ToolGroupRegistryValidationResult result =
                ToolGroupRegistryValidationResult.empty();

        assertTrue(result.valid());
        assertTrue(result.missingToolNames().isEmpty());
        assertTrue(result.ungroupedToolNames().isEmpty());
        assertTrue(result.summary().contains("valid=true"));
    }

    @Test
    public void validationResult_invalidWhenMissing() {
        ToolGroupRegistryValidationResult result =
                ToolGroupRegistryValidationResult.of(
                        List.of("missing_tool"), List.of());

        assertFalse(result.valid());
        assertTrue(result.missingToolNames().contains("missing_tool"));
    }

    @Test
    public void validationResult_invalidWhenUngrouped() {
        ToolGroupRegistryValidationResult result =
                ToolGroupRegistryValidationResult.of(
                        List.of(), List.of("ungrouped_tool"));

        assertFalse(result.valid());
        assertTrue(result.ungroupedToolNames().contains("ungrouped_tool"));
    }

    @Test
    public void validationResult_summaryContainsReason() {
        ToolGroupRegistryValidationResult result =
                ToolGroupRegistryValidationResult.of(
                        List.of("tool_x"), List.of("tool_y"));

        String s = result.summary();
        assertTrue(s.contains("valid=false"));
        assertTrue(s.contains("tool_x"));
        assertTrue(s.contains("tool_y"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void toolGroup_invalidRiskLevelThrowsException() {
        new ToolGroup(ToolGroupId.AC_GROUP, "test", "desc",
                List.of("tool_a"), List.of(), "INVALID", true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void toolGroup_nullRiskLevelThrowsException() {
        new ToolGroup(ToolGroupId.AC_GROUP, "test", "desc",
                List.of("tool_a"), List.of(), null, true);
    }

    @Test
    public void validationResult_listsAreImmutable() {
        ToolGroupRegistryValidationResult result =
                ToolGroupRegistryValidationResult.of(
                        List.of("a"), List.of("b"));

        assertThrows(UnsupportedOperationException.class,
                () -> result.missingToolNames().add("new"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.ungroupedToolNames().add("new"));
    }

    // ── Phase 2: LangChain4j 校验 ──

    @Test
    public void validateAgainstToolSpecifications_allMatchReturnsValid() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        // 模拟全量匹配：用 registry 的全部 toolName 构造 ToolSpecification
        List<String> allNames = registry.allToolNames();
        List<ToolSpecification> specs = new java.util.ArrayList<>();
        for (String name : allNames) {
            specs.add(ToolSpecification.builder().name(name).build());
        }

        ToolGroupRegistryValidationResult result =
                registry.validateAgainstToolSpecifications(specs);

        assertTrue(result.valid());
    }

    @Test
    public void validateAgainstToolSpecifications_detectsMissingToolName() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        // 构造比 registry 少的 spec 列表（只包含部分工具）
        ToolSpecification spec = ToolSpecification.builder()
                .name("set_ac_status").build();

        ToolGroupRegistryValidationResult result =
                registry.validateAgainstToolSpecifications(List.of(spec));

        assertFalse(result.valid());
        assertFalse(result.missingToolNames().isEmpty());
        assertTrue(result.missingToolNames().size() > 0);
    }

    @Test
    public void validateAgainstToolSpecifications_detectsUngroupedToolName() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        List<String> allNames = new java.util.ArrayList<>(registry.allToolNames());
        allNames.add("new_unknown_tool");
        List<ToolSpecification> specs = new java.util.ArrayList<>();
        for (String name : allNames) {
            specs.add(ToolSpecification.builder().name(name).build());
        }

        ToolGroupRegistryValidationResult result =
                registry.validateAgainstToolSpecifications(specs);

        assertFalse(result.valid());
        assertTrue(result.ungroupedToolNames().contains("new_unknown_tool"));
    }

    @Test
    public void validateAgainstToolSpecifications_bidirectionalWhenBothDiffer() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        // 用少量 toolName 构造 spec，同时包含 registry 没有的
        ToolSpecification existing = ToolSpecification.builder()
                .name("set_ac_status").build();
        ToolSpecification extra = ToolSpecification.builder()
                .name("extra_tool_not_in_registry").build();

        ToolGroupRegistryValidationResult result =
                registry.validateAgainstToolSpecifications(List.of(existing, extra));

        assertFalse(result.valid());
        assertFalse(result.missingToolNames().isEmpty());
        assertTrue(result.ungroupedToolNames().contains("extra_tool_not_in_registry"));
    }

    @Test
    public void validateAgainstRealManagerSpecs_allToolsMatch() {
        ToolGroupRegistry registry = ToolGroupRegistry.defaultRegistry();
        // 反射扫描全部 10 个生产 Manager class，合并真实 ToolSpecification
        List<Class<?>> managerClasses = List.of(
                WeatherUtils.class,
                VehicleDoorManager.class,
                VehicleWindowManager.class,
                VehicleSeatManager.class,
                VehicleAcManager.class,
                VehicleChassisManager.class,
                VehicleFragManager.class,
                VehicleSpeedManager.class,
                VehicleDMSManager.class,
                VlManager.class
        );
        java.util.Set<String> allRealNames = new java.util.LinkedHashSet<>();
        List<ToolSpecification> realSpecs = new java.util.ArrayList<>();
        for (Class<?> clazz : managerClasses) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(clazz)) {
                allRealNames.add(spec.name());
                realSpecs.add(spec);
            }
        }

        // 确保 toolName 唯一（无重复 @Tool name）
        assertEquals(allRealNames.size(), realSpecs.size());
        assertFalse("set_vehicle_spd must not be exposed to the model",
                allRealNames.contains("set_vehicle_spd"));

        ToolGroupRegistryValidationResult result =
                registry.validateAgainstToolSpecifications(realSpecs);

        // 删除 set_vehicle_spd 后，Registry 与真实 Manager Tool 仍应全量一致
        assertTrue("Registry-Spec mismatch: " + result.summary(), result.valid());
    }
}
