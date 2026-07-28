package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.toolgroup.ToolGroupSelectionResult;
import com.hirain.aiagent.toolgroup.ToolGroupRegistry;
import com.hirain.aiagent.toolgroup.ToolGroupId;
import com.hirain.aiagent.vision.routing.VisionIntentDecision;
import com.hirain.aiagent.vision.routing.VisionRequirement;
import com.hirain.aiagent.toolgroup.ToolGroupSelectionStatus;

import java.util.LinkedHashSet;

/**
 * 在 ToolGroup 最终装配前统一处理知识能力：
 * REQUIRED 请求收敛为知识 Tool；普通 TEXT 能力集合追加只读知识 Tool，使漏判时模型仍可自主检索。
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
        // 澄清与失败关闭属于 Runtime 安全终态，不能借“默认暴露”重新放宽工具边界。
        if (original.status() != ToolGroupSelectionStatus.SELECTED
                && original.status() != ToolGroupSelectionStatus.CHAT_ONLY) {
            return original;
        }
        LinkedHashSet<ToolGroupId> groups = new LinkedHashSet<>();
        if (original.status() == ToolGroupSelectionStatus.SELECTED) {
            groups.addAll(original.selectedGroupIds());
        }
        groups.remove(ToolGroupId.CHAT_ONLY_GROUP);
        groups.add(ToolGroupId.VEHICLE_KNOWLEDGE_GROUP);
        return ToolGroupSelectionResult.enriched(registry, java.util.List.copyOf(groups),
                "knowledge:auto:" + original.selectionReason(), original.confidence(), original.fallbackUsed());
    }
}
