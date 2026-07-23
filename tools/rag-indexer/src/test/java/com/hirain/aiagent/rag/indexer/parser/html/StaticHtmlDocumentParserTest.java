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

final class StaticHtmlDocumentParserTest {
    @TempDir
    Path directory;

    @Test
    void shouldPreserveStaticHeadingAndParagraphOrder() throws Exception {
        Path file = directory.resolve("manual.html");
        Files.writeString(file, "<main><h1 id='brakes'>Brakes</h1><p>Keep brake fluid at the specified level.</p></main>");

        var result = new StaticHtmlDocumentParser().parse(source(file));

        assertTrue(result.isSuccessful());
        assertEquals(2, result.blocks().size());
        assertEquals("Brakes", result.blocks().get(0).text());
        assertEquals("Brakes", result.blocks().get(1).locator().headingPath());
    }

    @Test
    void shouldRejectPageWhoseOnlyContentWasScript() throws Exception {
        Path file = directory.resolve("dynamic.html");
        Files.writeString(file, "<main><script>document.write('content')</script></main>");

        var result = new StaticHtmlDocumentParser().parse(source(file));

        assertEquals("DYNAMIC_HTML_UNSUPPORTED", result.diagnostics().get(0).reasonCode());
    }

    private SourceDocument source(Path file) {
        return new SourceDocument(file, SourceFormat.STATIC_HTML, new DocumentMetadata("manual", "Manual", "en"));
    }
}
