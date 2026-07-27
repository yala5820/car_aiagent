package com.hirain.aiagent.rag.model;

import java.util.List;

/** Parent 由哪些 Child 支撑及其最佳排名的内部诊断，不进入模型可见 ToolResult。 */
public record ParentRankingDiagnostics(
        String parentId,
        String bestChildId,
        List<String> supportingChildIds,
        String rankingSource,
        Double bestChildRerankScore,
        Integer bestChildRerankRank,
        Double bestChildFusionScore,
        Integer bestChildFusionRank,
        Integer bestChildDenseRank,
        Integer bestChildLexicalRank) {
    public ParentRankingDiagnostics {
        supportingChildIds = List.copyOf(supportingChildIds == null ? List.of() : supportingChildIds);
    }
}
