package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.vision.routing.VisionRequirement;

/**
 * 在 ToolGroup 最终装配前处理 V1 不支持的复合能力请求。
 * 本阶段不创建知识 ToolGroup：非复合结果原样保留，G703 在 Group 可用后负责 REQUIRED 的能力收敛。
 */
public final class KnowledgeCapabilityPlanner {
    public static final String COMPOUND_REQUEST_REQUIRES_SPLIT = "COMPOUND_REQUEST_REQUIRES_SPLIT";
    private final ToolGroupRegistry registry;
    public KnowledgeCapabilityPlanner() { this(ToolGroupRegistry.defaultRegistry()); }
    public KnowledgeCapabilityPlanner(ToolGroupRegistry registry) { this.registry=registry; }
    public ToolGroupSelectionResult plan(KnowledgeIntentDecision knowledge, VisionIntentDecision vision,
                                         ToolGroupSelectionResult original) {
        if (knowledge == null || original == null) return ToolGroupSelectionResult.failedClosed("KNOWLEDGE_PLANNER_INPUT_MISSING");
        boolean visionKnowledge = knowledge.requirement() == KnowledgeRequirement.REQUIRED && vision != null
                && vision.requirement() == VisionRequirement.REQUIRED;
        if (knowledge.compoundIntentDetected() || visionKnowledge) {
            return ToolGroupSelectionResult.clarificationRequired(COMPOUND_REQUEST_REQUIRES_SPLIT, original.confidence());
        }
        if (knowledge.requirement() == KnowledgeRequirement.REQUIRED) {
            return ToolGroupSelectionResult.selected(registry, java.util.List.of(ToolGroupId.VEHICLE_KNOWLEDGE_GROUP),
                    "knowledge:required:" + knowledge.reasonCode(), original.confidence());
        }
        return original;
    }
}
