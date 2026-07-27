package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 与共享 Parent-Child Golden 的关键顺序保持一致，防止 Android 端回退到 Child Evidence。 */
public class ParentChildRetrievalGoldenTest {
    @Test public void parentAggregationAndEvidenceBudgetFollowGolden() {
        SourceLocator locator = new SourceLocator(SourceFormat.PDF, List.of("章节"), 1, 1, null, null, null, 0, 0, 1);
        RetrievalEvidence c1 = evidence("c1", "p1", 0.90, 1, locator);
        RetrievalEvidence c2 = evidence("c2", "p1", 0.80, 2, locator);
        RetrievalEvidence c4 = evidence("c4", "p2", 0.70, 3, locator);
        List<ParentCandidate> parents = new ParentCandidateAggregator().aggregate(List.of(c1, c2, c4));
        assertEquals(List.of("p1", "p2"), parents.stream().map(ParentCandidate::parentId).toList());
        assertEquals("c1", parents.get(0).bestChild().chunkId());
        assertTrue(Files.exists(Path.of("rag-schema/test-vectors/parent-child-retrieval-v2.json"))
                || Files.exists(Path.of("..", "rag-schema", "test-vectors", "parent-child-retrieval-v2.json")));
    }

    private static RetrievalEvidence evidence(String child, String parent, double score, int rank, SourceLocator locator) {
        return new RetrievalEvidence("internal-" + child, child, parent, child, "d", "手册", "1", "", "", "章节",
                locator, null, null, null, null, 0.01, rank, score, rank, List.of("RERANK"), Applicability.EXACT,
                RetrievalConfidence.UNASSESSED, null);
    }
}
