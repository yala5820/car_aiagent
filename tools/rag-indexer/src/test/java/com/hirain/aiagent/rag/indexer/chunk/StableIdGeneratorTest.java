package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class StableIdGeneratorTest {
    @Test
    void shouldUseStableSha256InputsInsteadOfObjectIdentity() {
        ParentChunk parent = new ParentChunk(1, "manual", "Manual", "Brakes", "text", locator(), List.of(), List.of());
        ChildChunk child = new ChildChunk(1, 1, "same content", locator(), "TEXT");
        StableIdGenerator generator = new StableIdGenerator();

        assertEquals(generator.parentId(parent), generator.parentId(parent));
        assertEquals(generator.childId(child, generator.parentId(parent)), generator.childId(child, generator.parentId(parent)));
        assertNotEquals(generator.childId(child, generator.parentId(parent)), generator.childId(new ChildChunk(1, 1, "changed", locator(), "TEXT"), generator.parentId(parent)));
    }

    private SourceLocator locator() {
        return new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 1, 1, "Brakes", 1);
    }
}
