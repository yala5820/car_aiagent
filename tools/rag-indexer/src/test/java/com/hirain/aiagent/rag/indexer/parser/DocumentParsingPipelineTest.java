package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.cli.CliCommandException;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class DocumentParsingPipelineTest {
    @Test
    void shouldBlockResultWhoseLocatorCannotBeConsumedByDownstreamStages() {
        DocumentParser parser = new DocumentParser() {
            @Override public SourceFormat sourceFormat() { return SourceFormat.MARKDOWN; }
            @Override public ParseResult parse(SourceDocument document) {
                return new ParseResult(List.of(new StructuredBlock(BlockType.PARAGRAPH, "text",
                        new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1), null, ExtractionConfidence.HIGH)), List.of());
            }
        };
        DocumentParsingPipeline pipeline = new DocumentParsingPipeline(new DocumentParserRegistry(List.of(parser)), new ParseQualityValidator());
        SourceDocument source = new SourceDocument(Path.of("manual.md"), SourceFormat.MARKDOWN, new DocumentMetadata("manual", "Manual", "en"));

        CliCommandException exception = assertThrows(CliCommandException.class, () -> pipeline.parse(source));
        assertEquals("PARSE_QUALITY_GATE_FAILED_manual_LOCATOR_FORMAT_MISMATCH", exception.reasonCode());
    }
}
