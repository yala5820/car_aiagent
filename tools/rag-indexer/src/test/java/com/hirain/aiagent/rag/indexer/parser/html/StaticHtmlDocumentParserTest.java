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

    @Test
    void shouldPreserveHeadingLevelAndOrderedListGroup() throws Exception {
        Path file = directory.resolve("steps.html");
        Files.writeString(file, "<main><h1>Maintenance</h1><h2>Fluid</h2><ol><li>Open cap</li><li>Check level</li></ol></main>");
        var result = new StaticHtmlDocumentParser().parse(source(file));
        assertEquals(2, result.blocks().get(1).structure().headingLevel());
        assertEquals(com.hirain.aiagent.rag.indexer.model.BlockType.LIST_ITEM, result.blocks().get(2).type());
        assertEquals(com.hirain.aiagent.rag.indexer.model.SequenceType.ORDERED_STEPS, result.blocks().get(2).structure().sequenceType());
        assertEquals(result.blocks().get(2).structure().sequenceGroupId(), result.blocks().get(3).structure().sequenceGroupId());
    }

    @Test
    void shouldAssignDeterministicListGroupIdsAcrossRepeatedParses() throws Exception {
        Path file = directory.resolve("stable-steps.html");
        Files.writeString(file, "<main><h1>Maintenance</h1><ol><li>Open cap</li><li>Check level</li></ol><ul><li>Clean spill</li></ul></main>");

        var first = new StaticHtmlDocumentParser().parse(source(file));
        var second = new StaticHtmlDocumentParser().parse(source(file));

        assertEquals(first.blocks().get(1).structure().sequenceGroupId(), second.blocks().get(1).structure().sequenceGroupId());
        assertEquals(first.blocks().get(3).structure().sequenceGroupId(), second.blocks().get(3).structure().sequenceGroupId());
        assertEquals("html-list-1", first.blocks().get(1).structure().sequenceGroupId());
        assertEquals("html-list-2", first.blocks().get(3).structure().sequenceGroupId());
    }

    private SourceDocument source(Path file) {
        return new SourceDocument(file, SourceFormat.STATIC_HTML, new DocumentMetadata("manual", "Manual", "en"));
    }
}
