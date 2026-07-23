package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PdfCrossPageTableMergerTest {
    @Test
    void shouldMergeOnlyAdjacentTablesWithTheSameHeader() {
        List<TableBlock> merged = new PdfCrossPageTableMerger().merge(List.of(table(1, List.of("A", "B")), table(2, List.of("A", "B")), table(3, List.of("A", "C"))));

        assertEquals(2, merged.size());
        assertEquals(2, merged.get(0).rows().size());
        assertEquals(1, merged.get(0).locator().pdfPageStart());
        assertEquals(2, merged.get(0).locator().pdfPageEnd());
    }

    @Test
    void shouldNotMergeTablesWithDifferentHorizontalPositions() {
        TableBlock left = table(1, List.of("A", "B"));
        TableBlock right = new TableBlock("", List.of("A", "B"), List.of(List.of("x", "y")),
                new SourceLocator(SourceFormat.PDF, 2, 2, null, 0, 0, null, 2),
                TableExtractionMode.STREAM, ExtractionConfidence.MEDIUM, new BoundingBox(300, 100, 500, 300));

        assertEquals(2, new PdfCrossPageTableMerger().merge(List.of(left, right)).size());
    }

    private TableBlock table(int page, List<String> headers) {
        return new TableBlock("", headers, List.of(List.of("x", "y")),
                new SourceLocator(SourceFormat.PDF, page, page, null, 0, 0, null, page),
                TableExtractionMode.STREAM, ExtractionConfidence.MEDIUM, new BoundingBox(20, 100, 260, 300));
    }
}
