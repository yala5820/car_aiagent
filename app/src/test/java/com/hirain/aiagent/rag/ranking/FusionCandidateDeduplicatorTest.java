package com.hirain.aiagent.rag.ranking;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证 RRF 后完全重复、近重复和单 Parent 占位限制均保持确定性。 */
public class FusionCandidateDeduplicatorTest {
    @Test public void removesExactAndNearDuplicateContent() {
        Map<String, KnowledgeChunkEntity> chunks = new HashMap<>();
        chunks.put("c1", chunk("c1", "p1", "打开车门后，按下解锁按钮。"));
        chunks.put("c2", chunk("c2", "p2", "打开车门后，按下解锁按钮。"));
        chunks.put("c3", chunk("c3", "p3", "打开车门后，按下解锁按钮。确认。"));
        List<FusionCandidate> input = List.of(candidate("c1", 1), candidate("c2", 2), candidate("c3", 3));
        CandidateDeduplicationResult result = new FusionCandidateDeduplicator(30, 2, 0.80D).deduplicate(input, chunks);
        assertEquals(List.of("c1"), result.candidates().stream().map(FusionCandidate::chunkId).toList());
        assertEquals(2, result.records().size());
    }

    @Test public void limitsSameParentOccupancy() {
        Map<String, KnowledgeChunkEntity> chunks = new HashMap<>();
        chunks.put("c1", chunk("c1", "p1", "第一步：打开盖板。"));
        chunks.put("c2", chunk("c2", "p1", "第二步：取出滤芯。"));
        chunks.put("c3", chunk("c3", "p1", "第三步：装回盖板。"));
        CandidateDeduplicationResult result = new FusionCandidateDeduplicator(30, 2, 0.99D)
                .deduplicate(List.of(candidate("c1", 1), candidate("c2", 2), candidate("c3", 3)), chunks);
        assertEquals(2, result.candidates().size());
        assertEquals("PARENT_OCCUPANCY", result.records().get(0).reason());
    }

    private static FusionCandidate candidate(String id, int rank) {
        return new FusionCandidate(id, 1D / (60D + rank), rank, null, List.of("DENSE"));
    }

    private static KnowledgeChunkEntity chunk(String id, String parent, String content) {
        KnowledgeChunkEntity value = new KnowledgeChunkEntity();
        value.chunkId = id;
        value.parentChunkId = parent;
        value.content = content;
        return value;
    }
}
