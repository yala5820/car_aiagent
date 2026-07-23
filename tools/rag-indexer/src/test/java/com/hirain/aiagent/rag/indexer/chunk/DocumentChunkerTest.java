package com.hirain.aiagent.rag.indexer.chunk;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocumentChunkerTest {
    @Test
    void shouldCreateParentsByHeadingAndKeepWarningAtomic() {
        ParseResult parsed = new ParseResult(List.of(
                block(BlockType.HEADING, "Brakes", 1), block(BlockType.PARAGRAPH, "Maintain fluid level", 2),
                block(BlockType.WARNING, "Warning: do not drive with low fluid", 3),
                block(BlockType.HEADING, "Tires", 4), block(BlockType.PARAGRAPH, "Check pressure", 5)), List.of());

        ChunkResult result = new DocumentChunker(new ChunkBoundaryPolicy(32, 0, 2)).chunk(source(), parsed);

        assertEquals(2, result.parents().size());
        assertTrue(result.children().stream().anyMatch(chunk -> chunk.evidenceType().equals("WARNING") && chunk.text().contains("do not drive")));
    }

    private StructuredBlock block(BlockType type, String text, int line) {
        return new StructuredBlock(type, text, new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, line, line, "Brakes", line), null, ExtractionConfidence.HIGH);
    }

    private SourceDocument source() {
        return new SourceDocument(Path.of("manual.md"), SourceFormat.MARKDOWN, new DocumentMetadata("manual", "Manual", "en"));
    }
}
