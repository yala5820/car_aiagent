package com.hirain.aiagent.rag.indexer.evaluation;

import java.util.List;

/** Dense 指标与每题受控摘要；排名只保留 Chunk ID 和 Query SHA-256，不保存原始 Query 或正文。 */
public record RetrievalMetrics(int caseCount, double recallAt1, double recallAt3, double recallAt5, double meanReciprocalRank,
                               List<CaseResult> cases) {
    public RetrievalMetrics { cases = List.copyOf(cases); }
    public record CaseResult(String caseId, String querySha256, int firstRelevantRank, List<String> rankedChunkIds) {
        public CaseResult { rankedChunkIds = List.copyOf(rankedChunkIds); }
    }
}
