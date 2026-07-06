package com.hirain.aiagent.toolgroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import com.hirain.aiagent.intentrouter.KeywordIntentRouter;

import org.junit.Test;

import java.util.List;

public class DefaultToolGroupSelectorTest {

    private final DefaultToolGroupSelector selector =
            new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry());

    @Test
    public void select_vehicleAcReturnsAcAndBasicStatus() {
        IntentResult intent = IntentResult.of(IntentTag.VEHICLE_AC, IntentConfidence.HIGH,
                List.of("空调"), "打开空调", "TEXT", "matched:VEHICLE_AC");

        ToolGroupSelectionResult result = selector.select(intent, "打开空调");

        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue(result.selectedToolNames().contains("set_ac_status"));
        assertTrue(result.selectedToolNames().contains("set_ac_drive_temp"));
        assertEquals("intent:VEHICLE_AC", result.selectionReason());
        assertEquals(IntentConfidence.HIGH, result.confidence());
        assertFalse(result.fallbackUsed());
    }

    @Test
    public void select_weatherReturnsWeatherGroupOnly() {
        IntentResult intent = IntentResult.of(IntentTag.WEATHER, IntentConfidence.MEDIUM,
                List.of("天气"), "今天北京天气", "TEXT", "matched:WEATHER");

        ToolGroupSelectionResult result = selector.select(intent, "今天北京天气");

        assertEquals(List.of(ToolGroupId.WEATHER_GROUP), result.selectedGroupIds());
        assertEquals(List.of("getWeatherForecast"), result.selectedToolNames());
        assertEquals("intent:WEATHER", result.selectionReason());
    }

    @Test
    public void select_chatReturnsChatOnlyGroup() {
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "讲个笑话", "TEXT", "fallback_chat");

        ToolGroupSelectionResult result = selector.select(intent, "讲个笑话");

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), result.selectedGroupIds());
        assertTrue(result.selectedToolNames().isEmpty());
        assertEquals("intent:CHAT", result.selectionReason());
    }

    @Test
    public void select_unknownWithWeakVehicleKeywordReturnsCommonVehicle() {
        IntentResult intent = IntentResult.unknown("车窗好像有问题", "TEXT", "empty_text");

        ToolGroupSelectionResult result = selector.select(intent, "车窗好像有问题");

        assertEquals(List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue(result.selectedToolNames().contains("set_fl_window_status"));
        assertEquals("fallback:unknown_vehicle_keyword", result.selectionReason());
        assertTrue(result.fallbackUsed());
    }

    @Test
    public void select_unknownWithoutVehicleKeywordReturnsChatOnly() {
        IntentResult intent = IntentResult.unknown("随便聊聊", "TEXT", "empty_text");

        ToolGroupSelectionResult result = selector.select(intent, "随便聊聊");

        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), result.selectedGroupIds());
        assertTrue(result.selectedToolNames().isEmpty());
        assertEquals("fallback:unknown_chat", result.selectionReason());
        assertTrue(result.fallbackUsed());
    }

    @Test
    public void select_detectsAllVehicleAndVisionMappings() {
        // 覆盖 WINDOW / SEAT / DOOR / CHASSIS / FRAGRANCE / DMS / VISION_QA
        assertVehicleMapping(IntentTag.VEHICLE_WINDOW, ToolGroupId.WINDOW_GROUP, "set_fl_window_status");
        assertVehicleMapping(IntentTag.VEHICLE_SEAT, ToolGroupId.SEAT_GROUP, "set_seat_fl_heat");
        assertVehicleMapping(IntentTag.VEHICLE_DOOR, ToolGroupId.DOOR_GROUP, "set_door_lock");
        assertVehicleMapping(IntentTag.VEHICLE_CHASSIS, ToolGroupId.CHASSIS_GROUP, "set_chassis_mode");
        assertVehicleMapping(IntentTag.VEHICLE_FRAGRANCE, ToolGroupId.FRAGRANCE_GROUP, "set_frag_type");
        assertVehicleMapping(IntentTag.VEHICLE_DMS, ToolGroupId.DMS_GROUP, "set_dms_drive_fatigue");
    }

    @Test
    public void select_visionQaReturnsVisionGroup() {
        IntentResult intent = IntentResult.of(IntentTag.VISION_QA, IntentConfidence.MEDIUM,
                List.of("看到"), "看到前面有什么", "TEXT", "matched:VISION_QA");

        ToolGroupSelectionResult result = selector.select(intent, "看到前面有什么");

        assertEquals(List.of(ToolGroupId.VISION_GROUP), result.selectedGroupIds());
        assertEquals(List.of("front_camera_interaction"), result.selectedToolNames());
        assertEquals("intent:VISION_QA", result.selectionReason());
    }

    @Test
    public void select_chatWithVehicleKeywordFallsBackToCommonVehicle() {
        // "车里有点不舒服" → KeywordIntentRouter 返回 CHAT/LOW（无特定业务关键词）
        // DefaultToolGroupSelector 应识别弱车载关键词"车"，走车辆 fallback
        IntentResult intent = IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                List.of(), "车里有点不舒服", "TEXT", "fallback_chat");

        ToolGroupSelectionResult result = selector.select(intent, "车里有点不舒服");

        assertEquals(List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue("Should contain set_ac_status",
                result.selectedToolNames().contains("set_ac_status"));
        assertEquals("fallback:chat_vehicle_keyword", result.selectionReason());
        assertTrue(result.fallbackUsed());
    }

    @Test
    public void integration_keywordRouterAndToolGroupSelector() {
        // 端到端验证：KeywordIntentRouter + DefaultToolGroupSelector 组合行为
        KeywordIntentRouter router = new KeywordIntentRouter();
        DefaultToolGroupSelector selector = new DefaultToolGroupSelector(ToolGroupRegistry.defaultRegistry());

        // "车里有点不舒服" → KWR 无业务关键词命中 → CHAT/LOW
        // → selector 因含"车" → COMMON_VEHICLE_GROUP
        IntentResult chatWithVehicle = router.route("车里有点不舒服", "TEXT");
        assertEquals(IntentTag.CHAT, chatWithVehicle.intentTag());
        ToolGroupSelectionResult r1 = selector.select(chatWithVehicle, "车里有点不舒服");
        assertEquals(List.of(ToolGroupId.COMMON_VEHICLE_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                r1.selectedGroupIds());

        // "讲个笑话" → KWR 无关键词 → CHAT/LOW
        // → selector 无车关键词 → CHAT_ONLY_GROUP
        IntentResult chatOnly = router.route("讲个笑话", "TEXT");
        assertEquals(IntentTag.CHAT, chatOnly.intentTag());
        ToolGroupSelectionResult r2 = selector.select(chatOnly, "讲个笑话");
        assertEquals(List.of(ToolGroupId.CHAT_ONLY_GROUP), r2.selectedGroupIds());

        // "打开空调" → KWR 命中空调 → VEHICLE_AC
        // → selector → AC_GROUP + BASIC_STATUS_GROUP
        IntentResult ac = router.route("打开空调", "TEXT");
        assertEquals(IntentTag.VEHICLE_AC, ac.intentTag());
        ToolGroupSelectionResult r3 = selector.select(ac, "打开空调");
        assertEquals(List.of(ToolGroupId.AC_GROUP, ToolGroupId.BASIC_STATUS_GROUP),
                r3.selectedGroupIds());
    }

    private void assertVehicleMapping(IntentTag tag, ToolGroupId expectedGroup, String expectedTool) {
        String displayName = tag.name().replace("VEHICLE_", "").toLowerCase();
        IntentResult intent = IntentResult.of(tag, IntentConfidence.MEDIUM,
                List.of(displayName), "打开" + displayName, "TEXT", "matched:" + tag.name());
        ToolGroupSelectionResult result = selector.select(intent, "打开" + displayName);
        assertEquals(List.of(expectedGroup, ToolGroupId.BASIC_STATUS_GROUP),
                result.selectedGroupIds());
        assertTrue("Missing tool " + expectedTool + " for " + tag,
                result.selectedToolNames().contains(expectedTool));
        assertEquals("intent:" + tag.name(), result.selectionReason());
        assertFalse(result.fallbackUsed());
    }
}
