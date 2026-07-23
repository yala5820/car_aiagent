package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeDocumentEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KnowledgeDocumentEntityMapperTest {
    @Test
    void shouldCopyOnlyVerifiedCorpusMetadataAndStripPath() {
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntityMapper().map(StoreTestFixtures.document("PDF"), "UTF-8", 12);
        assertEquals("manual-1", entity.documentId); assertEquals("车辆手册", entity.documentTitle);
        assertEquals("manual.pdf", entity.sourceFileName); assertEquals("a".repeat(64), entity.sourceSha256);
        assertEquals("V1", entity.vehicleModel); assertEquals(12, entity.pageCount);
    }
}
