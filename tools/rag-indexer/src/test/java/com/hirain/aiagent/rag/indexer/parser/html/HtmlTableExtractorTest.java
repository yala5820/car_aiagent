package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HtmlTableExtractorTest {
    @TempDir
    Path directory;

    @Test
    void shouldExtractSimpleDomTable() throws Exception {
        Path file = write("<main><p>Enough static body content for this HTML document.</p><table aria-label='Capacity'><tr><th>Item</th><th>Value</th></tr><tr><td>Oil</td><td>3L</td></tr></table></main>");
        var result = new StaticHtmlDocumentParser().parse(source(file));

        assertTrue(result.isSuccessful());
        assertEquals("Capacity", result.tables().get(0).title());
        assertEquals(2, result.tables().get(0).headers().size());
    }

    @Test
    void shouldDiagnoseSpanInsteadOfGuessingExpansion() throws Exception {
        Path file = write("<main><p>Enough static body content for this HTML document.</p><table><tr><th colspan='2'>Item</th></tr><tr><td>Oil</td><td>3L</td></tr></table></main>");
        var result = new StaticHtmlDocumentParser().parse(source(file));

        assertEquals("HTML_TABLE_SPAN_UNSUPPORTED", result.diagnostics().get(0).reasonCode());
    }

    private Path write(String content) throws Exception {
        Path file = directory.resolve("manual.html");
        Files.writeString(file, content);
        return file;
    }

    private SourceDocument source(Path file) {
        return new SourceDocument(file, SourceFormat.STATIC_HTML, new DocumentMetadata("manual", "Manual", "en"));
    }
}
