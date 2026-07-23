package com.hirain.aiagent.rag.indexer.store;

import com.hirain.aiagent.rag.indexer.chunk.ChildChunk;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class KnowledgeChunkEntityMapperTest {
    private final KnowledgeChunkEntityMapper mapper = new KnowledgeChunkEntityMapper();

    @Test
    void shouldKeepParentVectorEmptyAndEncodeHeading() {
        KnowledgeChunkEntity entity = mapper.mapParent("parent", StoreTestFixtures.parent(StoreTestFixtures.locator(SourceFormat.PDF)), StoreTestFixtures.document("PDF"));
        assertNull(entity.embedding); assertEquals("", entity.parentChunkId); assertEquals("v1:8:制动 > 制动液", entity.headingPath);
    }

    @Test
    void shouldMapPdfLocatorAndClearOtherFormatFields() {
        KnowledgeChunkEntity entity = child(SourceFormat.PDF);
        assertEquals(2, entity.pdfPageStart); assertEquals(3, entity.pdfPageEnd); assertEquals("", entity.htmlElementId); assertEquals(0, entity.sourceLineStart);
    }

    @Test
    void shouldMapStaticHtmlLocatorAndClearOtherFormatFields() {
        KnowledgeChunkEntity entity = child(SourceFormat.STATIC_HTML);
        assertEquals("brake-fluid", entity.htmlElementId); assertEquals(0, entity.pdfPageStart); assertEquals(0, entity.sourceLineStart);
    }

    @Test
    void shouldMapMarkdownLocatorAndClearOtherFormatFields() {
        KnowledgeChunkEntity entity = child(SourceFormat.MARKDOWN);
        assertEquals(8, entity.sourceLineStart); assertEquals(10, entity.sourceLineEnd); assertEquals("", entity.htmlElementId); assertEquals(0, entity.pdfPageStart);
    }

    private KnowledgeChunkEntity child(SourceFormat format) {
        var locator = StoreTestFixtures.locator(format); var parent = StoreTestFixtures.parent(locator);
        return mapper.mapChild("child", "parent", parent, new ChildChunk(0, 0, "检查制动液", locator, "PROCEDURE"),
                StoreTestFixtures.document(format.name()), 3, vector());
    }

    private static float[] vector() { return new float[1024]; }
}
