package com.hirain.aiagent.core.postprocessor;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.ToolExecutionRecord;
import com.hirain.aiagent.core.component.PostProcessor;
import com.hirain.aiagent.tools.vision.FrontViewVisionResult;
import com.hirain.aiagent.tools.vision.VisionResultParser;

/** 仅在本轮已成功取得 Demo 视觉证据后追加非实时提醒。 */
public final class VisionGroundingPostProcessor implements PostProcessor {
    private static final String NOTICE = "\n\n提示：以上基于预置测试图片，不代表实时道路画面。";
    @Override public String process(String output, AgentLoopContext ctx) {
        if (output == null || output.contains("预置测试图片")) return output;
        for (ToolExecutionRecord record : ctx.toolExecutionHistory()) {
            if (!"front_camera_interaction".equals(record.toolName())) continue;
            FrontViewVisionResult result = VisionResultParser.parse(record.result());
            if (result != null && result.success && "SUCCESS".equals(result.status)) return output + NOTICE;
        }
        return output;
    }
}
