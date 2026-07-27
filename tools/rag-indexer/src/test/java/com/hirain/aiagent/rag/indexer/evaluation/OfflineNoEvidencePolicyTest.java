package com.hirain.aiagent.rag.indexer.evaluation;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证无证据阈值会忽略车型范围词，但保留真实功能短语。 */
public class OfflineNoEvidencePolicyTest {
    @Test public void rejectsOnlyScopeWordsAndUnrelatedContent() {
        KnowledgeChunkEntity parent = parent("车辆质量保证", "车辆质量保证涵盖材料和工艺问题。");
        OfflineNoEvidencePolicy policy = new OfflineNoEvidencePolicy();
        assertFalse(policy.hasSufficientEvidence("2026 中国大陆后驱版 Model Y 的电池容量是多少？", List.of(parent)));
        assertTrue(policy.hasSufficientEvidence("车辆质量保证涵盖什么？", List.of(parent)));
    }

    private static KnowledgeChunkEntity parent(String heading, String content) {
        KnowledgeChunkEntity value = new KnowledgeChunkEntity();
        value.headingPath = heading;
        value.content = content;
        return value;
    }
}
