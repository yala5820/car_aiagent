package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.model.*;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 完整 Parent 超出预算时不截断，后续 Parent 也不再拼接。 */
public class ParentEvidenceBudgetPolicyTest {
    @Test public void selectsWholeParentsAndStopsAtLimit() {
        RetrievalEvidence first = evidence("p1", "第一段完整内容。", 1);
        RetrievalEvidence second = evidence("p2", "第二段完整内容。", 2);
        List<RetrievalEvidence> result = new ParentEvidenceBudgetPolicy(1, 5000).select(List.of(first, second));
        assertEquals(1, result.size());
        assertEquals("p1", result.get(0).chunkId());
        assertEquals("第一段完整内容。", result.get(0).content());
    }

    private static RetrievalEvidence evidence(String id, String content, int rank) {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, List.of("章节"), 1, 1, null, null, null, 0, 0, rank);
        return new RetrievalEvidence("internal-" + id, id, null, content, "d", "手册", "1", "", "",
                "章节", locator, null, null, null, null, 0.01, rank, null, null,
                List.of("RRF"), Applicability.EXACT, RetrievalConfidence.UNASSESSED, null);
    }
}
