package com.hirain.aiagent.runtime;

import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.vision.routing.VisionRequirement;
import com.hirain.aiagent.rag.policy.KnowledgeIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeRequirement;

/** 统一从请求起点计算 TEXT 的 30/60 秒绝对期限。 */
public final class RequestDeadlinePolicy {
    public RequestDeadline resolve(long startedAtMs, KnowledgeIntentDecision knowledgeDecision,
                                   VisionIntentDecision visionDecision, ToolGroupSelectionResult selection) {
        if (knowledgeDecision != null && knowledgeDecision.requirement() == KnowledgeRequirement.REQUIRED) return RequestDeadline.knowledge(startedAtMs);
        return resolve(startedAtMs, visionDecision, selection);
    }
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
