package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.engines.scenematch.SceneMatch;

/**
 * 场景动作合并后处理器 — 根据场景名称在 LLM 输出中追加硬编码动作文本。
 * <p>
 * 这些硬编码动作模拟尚未实现的 SOA 控制（灯光、驾驶辅助等）。
 * 最终输出会由 {@link com.hirain.aiagent.core.collector.SummarizeMergeCollector SummarizeMergeCollector}
 * 合并为 80 字摘要。
 */
public class SceneActionMergePostProcessor implements PostProcessor {

    @Override
    public String process(String llmOutput, AgentLoopContext ctx) {
        SceneMatch.Scene scene = ctx.getContextData("scene", SceneMatch.Scene.class);
        if (scene == null) return llmOutput;

        String action = getHardcodedAction(scene.name);
        if (!action.isEmpty()) {
            ctx.putContextData("hardcoded_action", action);
        }
        return llmOutput;
    }

    public static String getHardcodedAction(String sceneName) {
        switch (sceneName) {
            case "驾驶员疲劳": return "方向盘震动已开启，并开启驾驶辅助。";
            case "舱外浓烟":   return "已打开双闪提醒后车。";
            case "雨雪天气":   return "已打开雾灯、示廓灯，已切换到雪地模式。";
            case "乘客休息":   return "已降低座舱系统音量。";
            case "工程施工":   return "已开双闪提醒后车，已关闭驾驶辅助。";
            default:           return "";
        }
    }
}
