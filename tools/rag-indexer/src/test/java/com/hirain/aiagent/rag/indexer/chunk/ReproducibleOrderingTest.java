package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReproducibleOrderingTest {
    @Test
    void shouldSortParentsAndChildrenByStableOrdinalKeys() {
        ParentChunk secondDocument = parent("z", 1);
        ParentChunk firstDocumentSecond = parent("a", 2);
        ParentChunk firstDocumentFirst = parent("a", 1);

        assertEquals(List.of(firstDocumentFirst, firstDocumentSecond, secondDocument),
                new ReproducibleOrdering().parents(List.of(secondDocument, firstDocumentSecond, firstDocumentFirst)));
        ChildChunk second = new ChildChunk(1, 2, "b", locator(), "TEXT");
        ChildChunk first = new ChildChunk(1, 1, "a", locator(), "TEXT");
        assertEquals(List.of(first, second), new ReproducibleOrdering().children(List.of(second, first)));
    }

    private ParentChunk parent(String documentId, int ordinal) {
        return new ParentChunk(ordinal, documentId, "Manual", "H", "text", locator(), List.of(), List.of());
    }

    private SourceLocator locator() {
        return new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 1, 1, "H", 1);
    }
}
