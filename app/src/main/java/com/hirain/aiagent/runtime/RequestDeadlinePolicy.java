package com.hirain.aiagent.runtime;

import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.vision.routing.VisionRequirement;

/** 统一从请求起点计算 TEXT 的 30/60 秒绝对期限。 */
public final class RequestDeadlinePolicy {
    public RequestDeadline resolve(long startedAtMs, VisionIntentDecision visionDecision,
                                   ToolGroupSelectionResult selection) {
        boolean plannedVisionTool = selection != null
                && selection.selectedToolNames().contains("front_camera_interaction");
        boolean visual = visionDecision != null
                && visionDecision.requirement() != VisionRequirement.NONE;
        return visual && plannedVisionTool
                ? RequestDeadline.vision(startedAtMs)
                : RequestDeadline.standard(startedAtMs);
    }
}
