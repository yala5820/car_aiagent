package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TableChunkSplitterTest {
    @Test
    void shouldRepeatTableSemanticsForEverySplitChild() {
        TableBlock table = new TableBlock("Capacity", List.of("Item", "Value"),
                List.of(List.of("Oil", "3L"), List.of("Coolant", "5L"), List.of("Brake fluid", "1L")),
                new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, "Maintenance", 1),
                TableExtractionMode.STREAM, ExtractionConfidence.MEDIUM);

        List<String> children = new TableChunkSplitter().split(table, 2);

        assertEquals(2, children.size());
        assertTrue(children.stream().allMatch(text -> text.contains("Capacity：Item=")));
        assertTrue(children.get(1).contains("Brake fluid"));
    }
}
