package com.hirain.aiagent.rag.document;

import com.hirain.aiagent.rag.model.SourceFormat;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** 验证共享 Store 的 0/空字段严格映射为领域“不适用”而非虚构位置。 */
public class SourceLocatorEntityMapperTest {
    @Test
    public void mapsHtmlWithoutPdfOrMarkdownLocation() {
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.sourceFormat = "STATIC_HTML";
        entity.headingPath = "v1:2:系统2:升级";
        entity.pdfPageStart = 0; entity.pdfPageEnd = 0;
        entity.sourceLineStart = 0; entity.sourceLineEnd = 0;
        entity.htmlElementId = "upgrade-condition"; entity.sectionOrdinal = 4;

        var locator = new SourceLocatorEntityMapper().fromChunk(entity);

        assertEquals(SourceFormat.STATIC_HTML, locator.sourceFormat());
        assertEquals(0, locator.pdfPageStart());
        assertEquals(0, locator.sourceLineStart());
        assertEquals("upgrade-condition", locator.htmlElementId());
        assertEquals("系统", locator.headingPath().get(0));
    }

    @Test
    public void mapsMarkdownLineRangeWithoutHtmlAnchor() {
        KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
        entity.sourceFormat = "MARKDOWN";
        entity.headingPath = "v1:4:P001";
        entity.pdfPageStart = 0; entity.pdfPageEnd = 0;
        entity.sourceLineStart = 120; entity.sourceLineEnd = 128;
        entity.htmlElementId = ""; entity.sectionOrdinal = 2;

        var locator = new SourceLocatorEntityMapper().fromChunk(entity);

        assertEquals(SourceFormat.MARKDOWN, locator.sourceFormat());
        assertEquals(120, locator.sourceLineStart());
        assertNull(locator.htmlElementId());
    }
}
