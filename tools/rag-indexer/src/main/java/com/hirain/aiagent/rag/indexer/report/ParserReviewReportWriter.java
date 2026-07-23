package com.hirain.aiagent.rag.indexer.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.pipeline.DocumentBuildState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 输出供人工复核的解析索引，不包含文档正文、Chunk、Embedding、绝对路径或凭证。
 *
 * <p>审核人以 documentId、相对路径和 PDF 页号/HTML 段序号回到经授权原件复核，不能通过本报告还原正文。</p>
 */
public final class ParserReviewReportWriter {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public void write(Path file, String runId, String knowledgeScopeId, List<DocumentBuildState> states) throws IOException {
        if (Files.exists(file)) {
            throw new IllegalArgumentException("解析审核报告已存在，禁止覆盖");
        }
        List<DocumentReview> documents = states.stream()
                .map(this::document)
                .sorted(Comparator.comparing(DocumentReview::documentId))
                .toList();
        json.writeValue(file.toFile(), new ParserReviewReport(1, runId, knowledgeScopeId, documents));
    }

    private DocumentReview document(DocumentBuildState state) {
        ParseResult result = state.parseResult();
        List<DiagnosticReview> diagnostics = result.diagnostics().stream()
                .map(value -> new DiagnosticReview(value.reasonCode(), value.severity().name(), locator(value)))
                .sorted(Comparator.comparing(DiagnosticReview::reasonCode)
                        .thenComparing(value -> value.locator().pdfPageStart()))
                .toList();
        List<WarningLocatorReview> warnings = result.blocks().stream()
                .filter(block -> block.type().name().equals("WARNING"))
                .map(ParserReviewReportWriter::warning)
                .sorted(Comparator.comparing(WarningLocatorReview::pdfPageStart).thenComparing(WarningLocatorReview::sectionOrdinal))
                .toList();
        return new DocumentReview(state.corpusDocument().documentId(), state.corpusDocument().relativePath(),
                state.corpusDocument().sourceFormat(), result.blocks().size(), result.tables().size(), diagnostics, warnings);
    }

    private static LocatorReview locator(ParseDiagnostic diagnostic) {
        var locator = diagnostic.locator();
        return new LocatorReview(locator.pdfPageStart(), locator.pdfPageEnd(), locator.sourceLineStart(), locator.sourceLineEnd(), locator.sectionOrdinal());
    }

    private static WarningLocatorReview warning(StructuredBlock block) {
        var locator = block.locator();
        return new WarningLocatorReview(locator.pdfPageStart(), locator.pdfPageEnd(), locator.sourceLineStart(),
                locator.sourceLineEnd(), locator.sectionOrdinal(), block.confidence().name());
    }

    /** JSON 顶层契约：仅定位与计数可进入 work 审核报告。 */
    public record ParserReviewReport(int schemaVersion, String runId, String knowledgeScopeId, List<DocumentReview> documents) { }

    public record DocumentReview(String documentId, String sourceRelativePath, String sourceFormat, int blockCount, int tableCount,
                                 List<DiagnosticReview> diagnostics, List<WarningLocatorReview> warningLocators) { }

    public record DiagnosticReview(String reasonCode, String severity, LocatorReview locator) { }

    public record LocatorReview(int pdfPageStart, int pdfPageEnd, int sourceLineStart, int sourceLineEnd, int sectionOrdinal) { }

    public record WarningLocatorReview(int pdfPageStart, int pdfPageEnd, int sourceLineStart, int sourceLineEnd,
                                       int sectionOrdinal, String confidence) { }
}
