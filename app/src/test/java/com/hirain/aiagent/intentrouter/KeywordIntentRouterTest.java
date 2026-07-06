package com.hirain.aiagent.intentrouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeywordIntentRouterTest {

    @Test
    public void route_detectsVehicleAc() {
        IntentResult result = new KeywordIntentRouter().route("把空调温度调到二十二度", "TEXT");
        assertEquals(IntentTag.VEHICLE_AC, result.intentTag());
        assertTrue(result.matchedKeywords().contains("空调"));
        assertEquals("TEXT", result.sourceInputType());
    }

    @Test
    public void route_detectsVehicleWindow() {
        IntentResult result = new KeywordIntentRouter().route("帮我打开车窗", "TEXT");
        assertEquals(IntentTag.VEHICLE_WINDOW, result.intentTag());
    }

    @Test
    public void route_detectsWeather() {
        IntentResult result = new KeywordIntentRouter().route("今天北京天气怎么样", "TEXT");
        assertEquals(IntentTag.WEATHER, result.intentTag());
    }

    @Test
    public void route_detectsAllFirstVersionVehicleTags() {
        KeywordIntentRouter router = new KeywordIntentRouter();

        assertEquals(IntentTag.VEHICLE_SEAT, router.route("打开座椅加热", "TEXT").intentTag());
        assertEquals(IntentTag.VEHICLE_DOOR, router.route("帮我锁车门", "TEXT").intentTag());
        assertEquals(IntentTag.VEHICLE_CHASSIS, router.route("切换到运动模式", "TEXT").intentTag());
        assertEquals(IntentTag.VEHICLE_FRAGRANCE, router.route("打开香氛", "TEXT").intentTag());
        assertEquals(IntentTag.VEHICLE_DMS, router.route("驾驶员是不是疲劳了", "TEXT").intentTag());
        assertEquals(IntentTag.VISION_QA, router.route("前方摄像头看到什么", "TEXT").intentTag());
    }

    @Test
    public void route_fallsBackToChatForNormalText() {
        IntentResult result = new KeywordIntentRouter().route("你好，给我讲个笑话", "TEXT");
        assertEquals(IntentTag.CHAT, result.intentTag());
        assertEquals(IntentConfidence.LOW, result.confidence());
    }

    @Test
    public void route_returnsUnknownForBlankText() {
        IntentResult result = new KeywordIntentRouter().route("   ", "TEXT");
        assertEquals(IntentTag.UNKNOWN, result.intentTag());
        assertEquals(IntentConfidence.NONE, result.confidence());
    }

    @Test
    public void route_returnsUnknownForNullText() {
        IntentResult result = new KeywordIntentRouter().route(null, "TEXT");
        assertEquals(IntentTag.UNKNOWN, result.intentTag());
        assertEquals(IntentConfidence.NONE, result.confidence());
        assertEquals("empty_text", result.debugReason());
    }

    @Test
    public void route_marksPriorityTieInDebugReason() {
        IntentResult result = new KeywordIntentRouter().route("车窗看见了吗", "TEXT");
        // "车窗" 匹配 VEHICLE_WINDOW，"看见" 匹配 VISION_QA
        // 两者命中数相同，按固定优先级选 VEHICLE_WINDOW
        assertEquals(IntentTag.VEHICLE_WINDOW, result.intentTag());
        assertEquals("matched:VEHICLE_WINDOW:priority", result.debugReason());
    }

    @Test
    public void route_detectsVehicleDmsEnglishKeyword() {
        // 用户输入英文 DMS，normalize 后为 "dms"，应命中 VEHICLE_DMS
        IntentResult result = new KeywordIntentRouter().route("DMS是什么状态", "TEXT");
        assertEquals(IntentTag.VEHICLE_DMS, result.intentTag());
    }
}
