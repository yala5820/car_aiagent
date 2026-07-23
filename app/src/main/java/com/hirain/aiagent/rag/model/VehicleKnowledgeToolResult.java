package com.hirain.aiagent.rag.model;

import java.util.List;

/** 模型 ToolResult 的稳定白名单 Schema；answerable=false 时 Evidence 必须为空。 */
public record VehicleKnowledgeToolResult(int schemaVersion, RagStatus status, boolean answerable, String query,
                                         List<VehicleKnowledgeEvidence> evidence, List<String> degradedReasons,
                                         RagFailureReason failureReasonCode, String userSafeMessage) {
    public VehicleKnowledgeToolResult {
        evidence = List.copyOf(evidence == null ? List.of() : evidence); degradedReasons = List.copyOf(degradedReasons == null ? List.of() : degradedReasons);
        if (!answerable && !evidence.isEmpty()) throw new IllegalArgumentException("不可回答结果不能携带 Evidence 正文");
    }
}
