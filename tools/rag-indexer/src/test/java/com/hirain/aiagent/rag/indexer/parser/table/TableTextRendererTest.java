package com.hirain.aiagent.rag.indexer.parser.table;

import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TableTextRendererTest {
    @Test
    void shouldRepeatTitleAndHeadersForEachRow() {
        TableBlock table = new TableBlock("保养参数", List.of("项目", "容量"), List.of(List.of("制动液", "3L")),
                new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1), TableExtractionMode.STREAM, ExtractionConfidence.MEDIUM);

        assertEquals(List.of("保养参数：项目=制动液；容量=3L"), new TableTextRenderer().renderRows(table));
    }
}
