package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParseQualityValidatorTest {
    @Test
    void shouldAcceptEachFormatWhenItsLocatorMatchesProtocol() {
        ParseQualityValidator validator = new ParseQualityValidator();

        assertTrue(validator.validate(SourceFormat.PDF, result(SourceFormat.PDF, new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1))).isAccepted());
        assertTrue(validator.validate(SourceFormat.STATIC_HTML, result(SourceFormat.STATIC_HTML, new SourceLocator(SourceFormat.STATIC_HTML, 0, 0, "x", 0, 0, "H", 1))).isAccepted());
        assertTrue(validator.validate(SourceFormat.MARKDOWN, result(SourceFormat.MARKDOWN, new SourceLocator(SourceFormat.MARKDOWN, 0, 0, null, 1, 1, "H", 1))).isAccepted());
    }

    @Test
    void shouldRejectFormatMismatchedLocator() {
        ParseQualityReport report = new ParseQualityValidator().validate(SourceFormat.MARKDOWN,
                result(SourceFormat.MARKDOWN, new SourceLocator(SourceFormat.PDF, 1, 1, null, 0, 0, null, 1)));

        assertFalse(report.isAccepted());
    }

    private ParseResult result(SourceFormat format, SourceLocator locator) {
        return new ParseResult(List.of(new StructuredBlock(BlockType.PARAGRAPH, "text", locator, null, ExtractionConfidence.HIGH)), List.of());
    }
}
