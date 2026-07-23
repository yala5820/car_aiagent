package com.hirain.aiagent.rag.indexer.parser;

import com.hirain.aiagent.rag.indexer.model.DiagnosticSeverity;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;

import java.util.ArrayList;
import java.util.List;

/** 三格式共同的输出质量门禁；后续阶段不得将未通过结果 Chunk、Embedding 或写入 Store。 */
public final class ParseQualityValidator {
    public ParseQualityReport validate(SourceFormat format, ParseResult result) {
        List<String> failures = new ArrayList<>();
        if (result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)) {
            failures.add("PARSE_ERROR_DIAGNOSTIC");
        }
        result.blocks().forEach(block -> validateLocator(format, block.locator(), failures));
        result.tables().forEach(table -> validateTable(format, table, failures));
        return new ParseQualityReport(failures.stream().distinct().toList());
    }

    private void validateTable(SourceFormat format, TableBlock table, List<String> failures) {
        validateLocator(format, table.locator(), failures);
        if (table.rows().stream().anyMatch(row -> row.size() != table.headers().size())) {
            failures.add("TABLE_LOCATOR_OR_COLUMNS_INVALID");
        }
    }

    private void validateLocator(SourceFormat format, SourceLocator locator, List<String> failures) {
        if (locator == null || locator.sourceFormat() != format) {
            failures.add("LOCATOR_FORMAT_MISMATCH");
            return;
        }
        if (format == SourceFormat.PDF && (locator.pdfPageStart() < 1 || locator.pdfPageEnd() < locator.pdfPageStart())) {
            failures.add("PDF_LOCATOR_INVALID");
        }
        if (format == SourceFormat.MARKDOWN && locator.sourceLineStart() < 1) {
            failures.add("MARKDOWN_LOCATOR_INVALID");
        }
        if (format == SourceFormat.STATIC_HTML && locator.sectionOrdinal() < 1) {
            failures.add("HTML_LOCATOR_INVALID");
        }
    }
}
