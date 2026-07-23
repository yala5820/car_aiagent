package com.hirain.aiagent.rag.indexer.parser.markdown;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MarkdownDocumentParserTest {
    @TempDir
    Path directory;

    @Test
    void shouldParseHeadingsParagraphsAndCodeInOrder() throws Exception {
        Path file = write("---\ntitle: ignored\n---\n# Brakes\nKeep fluid full.\n```\ncode sample\n```");
        var result = new MarkdownDocumentParser().parse(source(file));

        assertTrue(result.isSuccessful());
        assertEquals(List.of(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CODE), result.blocks().stream().map(block -> block.type()).toList());
        assertEquals("Brakes", result.blocks().get(1).locator().headingPath());
        assertEquals(4, result.blocks().get(0).locator().sourceLineStart());
    }

    @Test
    void shouldDiagnoseRawHtmlWithoutExecutingIt() throws Exception {
        var result = new MarkdownDocumentParser().parse(source(write("# H\n<div onclick='bad()'>text</div>")));

        assertTrue(result.diagnostics().stream().anyMatch(item -> item.reasonCode().equals("MARKDOWN_RAW_HTML_IGNORED")));
    }

    @Test
    void shouldExtractGfmTableAsStructuredTable() throws Exception {
        var result = new MarkdownDocumentParser().parse(source(write("# Capacity\n| Item | Value |\n| --- | --- |\n| Oil | 3L |")));

        assertEquals(1, result.tables().size());
        assertEquals(List.of("Item", "Value"), result.tables().get(0).headers());
        assertEquals("Capacity", result.tables().get(0).locator().headingPath());
    }

    private Path write(String text) throws Exception {
        Path file = directory.resolve("manual.md");
        Files.writeString(file, text);
        return file;
    }

    private SourceDocument source(Path file) {
        return new SourceDocument(file, SourceFormat.MARKDOWN, new DocumentMetadata("manual", "Manual", "en"));
    }
}
