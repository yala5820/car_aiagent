package com.hirain.aiagent.intentrouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于关键词表和少量正则的 IntentRouter 默认实现。
 * <p>
 * 使用 LinkedHashMap 保持匹配优先级稳定。匹配策略：
 * <ol>
 *   <li>空文本 → UNKNOWN/NONE</li>
 *   <li>每个 tag 按字符串关键词 contains() 和正则 find() 计分</li>
 *   <li>全局无命中 → CHAT/LOW</li>
 *   <li>单 tag 命中数最高 → 该 tag；平局时按 LinkedHashMap 中靠前者胜出</li>
 * </ol>
 * 不调用 LLM、不引用 tool registry、不抛出业务异常。
 */
public class KeywordIntentRouter implements IntentRouter {

    // ── 正则规则 ──
    private static final Pattern TEMP_ADJUST_PATTERN = Pattern.compile("温度.*调到|调到.*度");
    private static final Pattern WINDOW_ADJUST_PATTERN = Pattern.compile("升.*窗|降.*窗");

    // ── 关键词规则（LinkedHashMap 保证优先级顺序） ──
    private final LinkedHashMap<IntentTag, List<String>> keywordRules = new LinkedHashMap<>();
    private final LinkedHashMap<IntentTag, List<Pattern>> regexRules = new LinkedHashMap<>();

    public KeywordIntentRouter() {
        // 按优先级从高到低注册
        keywordRules.put(IntentTag.VEHICLE_AC, Arrays.asList("空调", "制冷", "制热", "风量", "除雾"));
        regexRules.put(IntentTag.VEHICLE_AC, Arrays.asList(TEMP_ADJUST_PATTERN));

        keywordRules.put(IntentTag.VEHICLE_WINDOW, Arrays.asList("车窗", "窗户", "开窗", "关窗", "升窗", "降窗", "遮阳帘", "天窗"));
        regexRules.put(IntentTag.VEHICLE_WINDOW, Arrays.asList(WINDOW_ADJUST_PATTERN));

        keywordRules.put(IntentTag.VEHICLE_SEAT, Arrays.asList("座椅", "座位", "靠背", "腰托", "座椅加热", "座椅通风", "按摩"));

        keywordRules.put(IntentTag.VEHICLE_DOOR, Arrays.asList("车门", "门锁", "上锁", "解锁", "锁车", "开门", "关门"));

        keywordRules.put(IntentTag.VEHICLE_CHASSIS, Arrays.asList("底盘", "悬架", "驾驶模式", "运动模式", "舒适模式", "越野", "雪地"));

        keywordRules.put(IntentTag.VEHICLE_FRAGRANCE, Arrays.asList("香氛", "香味", "香薰", "空气清新"));

        keywordRules.put(IntentTag.VEHICLE_DMS, Arrays.asList("疲劳", "分心", "驾驶员监测", "dms"));

        keywordRules.put(IntentTag.VISION_QA, Arrays.asList("看到", "看见", "前方", "摄像头", "画面", "图片", "照片"));

        keywordRules.put(IntentTag.WEATHER, Arrays.asList("天气", "下雨", "下雪", "气温", "湿度", "预报", "刮风"));
    }

    @Override
    public IntentResult route(String text, String sourceInputType) {
        String normalized = normalize(text);
        String inputType = sourceInputType != null ? sourceInputType : "TEXT";

        if (normalized == null || normalized.isEmpty()) {
            return IntentResult.unknown(text != null ? text.trim() : "", inputType, "empty_text");
        }

        // ── 统计每个 tag 的命中数和命中词 ──
        Map<IntentTag, Integer> scores = new LinkedHashMap<>();
        Map<IntentTag, List<String>> tagKeywords = new LinkedHashMap<>();

        for (Map.Entry<IntentTag, List<String>> entry : keywordRules.entrySet()) {
            int count = 0;
            List<String> matched = new ArrayList<>();
            for (String keyword : entry.getValue()) {
                if (normalized.contains(keyword)) {
                    count++;
                    matched.add(keyword);
                }
            }
            // 正则匹配
            List<Pattern> patterns = regexRules.getOrDefault(entry.getKey(), List.of());
            for (Pattern pattern : patterns) {
                if (pattern.matcher(normalized).find()) {
                    count++;
                    matched.add("regex:" + entry.getKey().name());
                }
            }
            if (count > 0) {
                scores.put(entry.getKey(), count);
                tagKeywords.put(entry.getKey(), matched);
            }
        }

        // ── 无命中 → CHAT/LOW ──
        if (scores.isEmpty()) {
            return IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW,
                    List.of(), normalized, inputType, "fallback_chat");
        }

        // ── 选最高分 tag ──
        IntentTag winner = null;
        IntentTag tieWinner = null;
        int maxScore = 0;
        boolean tie = false;

        for (Map.Entry<IntentTag, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                winner = entry.getKey();
                tie = false;
            } else if (entry.getValue() == maxScore) {
                tie = true;
                tieWinner = winner; // 保留第一个（优先级高）
            }
        }

        // ── 确定 debugReason ──
        String debugReason;
        if (tie && tieWinner != null) {
            debugReason = "matched:" + tieWinner.name() + ":priority";
            winner = tieWinner;
        } else {
            debugReason = "matched:" + winner.name();
        }

        // ── 置信度 ──
        IntentConfidence confidence = maxScore >= 2 ? IntentConfidence.HIGH : IntentConfidence.MEDIUM;

        return IntentResult.of(winner, confidence, tagKeywords.getOrDefault(winner, List.of()), normalized, inputType, debugReason);
    }

    /**
     * 标准化文本：null → null，trim()，转小写。
     */
    private static String normalize(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }
}
