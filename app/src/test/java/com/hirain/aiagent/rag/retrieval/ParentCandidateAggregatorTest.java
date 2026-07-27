package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.*;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证多个 Child 命中同一 Parent 时只保留一个 Parent，并以最佳 Rerank Child 代表排序。 */
public class ParentCandidateAggregatorTest {
    @Test public void aggregatesChildrenByParentAndUsesBestRerankScore() {
        RetrievalEvidence first = evidence("c1", "p1", 0.7, 2);
        RetrievalEvidence best = evidence("c2", "p1", 0.9, 1);
        RetrievalEvidence other = evidence("c3", "p2", 0.8, 3);
        List<ParentCandidate> result = new ParentCandidateAggregator().aggregate(List.of(first, best, other));
        assertEquals(2, result.size());
        assertEquals("p1", result.get(0).parentId());
        assertEquals("c2", result.get(0).bestChild().chunkId());
        assertEquals(List.of("c1", "c2"), result.get(0).supportingChildIds());
    }

    private static RetrievalEvidence evidence(String child, String parent, double score, int rank) {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, List.of("车辆维护"), 1, 1, null, null, null, 0, 0, 1);
        return new RetrievalEvidence("internal-" + child, child, parent, child, "d", "手册", "1", "", "",
                "车辆维护", locator, null, null, null, null, 0.01, rank, score, rank,
                List.of("RERANK"), Applicability.EXACT, RetrievalConfidence.UNASSESSED, null);
    }
}
