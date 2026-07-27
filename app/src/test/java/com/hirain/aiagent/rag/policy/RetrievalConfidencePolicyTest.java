package com.hirain.aiagent.rag.policy;

import static org.junit.Assert.assertEquals;

import com.hirain.aiagent.rag.model.RetrievalConfidence;

import org.junit.Test;

/** 校准阈值只作用于 Rerank，RRF 不得伪造同一置信度语义。 */
public class RetrievalConfidencePolicyTest {
    private final RetrievalConfidencePolicy policy = new RetrievalConfidencePolicy();

    @Test public void classifiesCalibratedRerankBands() {
        assertEquals(RetrievalConfidence.HIGH, policy.classify("RERANK", 0.95, 0.06));
        assertEquals(RetrievalConfidence.MEDIUM, policy.classify("RERANK", 0.91, 0.03));
        assertEquals(RetrievalConfidence.LOW, policy.classify("RERANK", 0.99, 0.01));
    }

    @Test public void leavesRrfAndIncompleteScoresUnassessed() {
        assertEquals(RetrievalConfidence.UNASSESSED, policy.classify("RRF", 0.99, 0.50));
        assertEquals(RetrievalConfidence.UNASSESSED, policy.classify("RERANK", null, 0.50));
    }
}
