package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.DiagnosticSeverity;
import com.hirain.aiagent.rag.indexer.config.PdfParserConfig;
import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.ParseResult;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.StructuredBlock;
import com.hirain.aiagent.rag.indexer.model.BlockType;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.parser.DocumentParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * V1 PDF 正文入口：仅接受纯文本页 PDF，以及无需密码且显式允许内容提取的只读加密 PDF。
 * 页级空文本会形成 MIXED 并被拒绝，防止扫描页被静默遗漏。
 */
public final class PdfDocumentParser implements DocumentParser {
    private final PdfDocumentClassifier classifier = new PdfDocumentClassifier();
    private final PdfLayoutAnalyzer layoutAnalyzer = new PdfLayoutAnalyzer();
    private final PdfUnsafeContentInspector unsafeContentInspector = new PdfUnsafeContentInspector();
    private final PdfTableExtractor tableExtractor = new TabulaPdfTableExtractor();
    private final PdfParserConfig pdfConfig;

    public PdfDocumentParser() {
        this(new PdfParserConfig("AUTO", List.of()));
    }

    public PdfDocumentParser(PdfParserConfig pdfConfig) {
        this.pdfConfig = pdfConfig;
    }

    @Override
    public SourceFormat sourceFormat() {
        return SourceFormat.PDF;
    }

    @Override
    public ParseResult parse(SourceDocument document) {
        PdfClassification classification = classifier.classify(document.path());
        if (classification != PdfClassification.TEXT) {
            return failure(document, "PDF_" + classification.name(), "PDF 分类不受 V1 支持：" + classification);
        }
        try {
            List<PdfPageLayout> layouts = layoutAnalyzer.analyze(document.path());
            Set<String> headersAndFooters = new PdfHeaderFooterDetector().detectRepeatedEdgeLines(layouts);
            List<ParseDiagnostic> diagnostics = new java.util.ArrayList<>();
            for (String finding : unsafeContentInspector.inspect(document.path())) {
                diagnostics.add(new ParseDiagnostic(finding, DiagnosticSeverity.WARNING, document.metadata().documentId(),
                        locator(0), "PDF 主动内容已忽略，未执行也未提取为语料"));
            }
            Set<String> diagnosedEdges = new HashSet<>();
            for (PdfPageLayout page : layouts) {
                for (PdfTextLine line : page.lines()) {
                    if (headersAndFooters.contains(line.text().replaceAll("\\s+", " ").trim())) {
                        if (diagnosedEdges.add(line.text())) {
                            diagnostics.add(new ParseDiagnostic("PDF_REPEATED_HEADER_OR_FOOTER", DiagnosticSeverity.WARNING,
                                    document.metadata().documentId(), locator(page.physicalPage()), "重复页眉或页脚已从正文排除"));
                        }
                    }
                }
            }
            List<StructuredBlock> blocks = new PdfWarningDetector().markWarnings(
                    new PdfBlockAssembler().assemble(layouts, headersAndFooters));
            for (StructuredBlock block : blocks) {
                if (block.type() == BlockType.HEADING && block.structure().headingLevel() == 0) {
                    diagnostics.add(new ParseDiagnostic("PDF_HEADING_LEVEL_UNRESOLVED", DiagnosticSeverity.WARNING,
                            document.metadata().documentId(), block.locator(), "标题候选缺少可靠层级，仅保留为标题文本且不伪造深层路径"));
                }
            }
            List<TableBlock> tables = extractTables(document, diagnostics);
            return new ParseResult(blocks, tables, diagnostics);
        } catch (Exception exception) {
            return failure(document, "PDF_EXTRACTION_FAILED", "PDF 文本提取失败");
        }
    }

    private ParseResult failure(SourceDocument document, String reasonCode, String summary) {
        ParseDiagnostic diagnostic = new ParseDiagnostic(reasonCode, DiagnosticSeverity.ERROR,
                document.metadata().documentId(), new SourceLocator(SourceFormat.PDF, 0, 0, null, 0, 0, null, 0), summary);
        return new ParseResult(List.of(), List.of(diagnostic));
    }

    private SourceLocator locator(int physicalPage) {
        return new SourceLocator(SourceFormat.PDF, physicalPage, physicalPage, null, 0, 0, null, physicalPage);
    }

    private List<TableBlock> extractTables(SourceDocument document, List<ParseDiagnostic> diagnostics) {
        try {
            return new PdfCrossPageTableMerger().merge(tableExtractor.extract(document.path(), page ->
                    PdfTableStrategy.valueOf(pdfConfig.tableStrategyFor(document.metadata().documentId(), page))));
        } catch (Exception exception) {
            diagnostics.add(new ParseDiagnostic("PDF_TABLE_EXTRACTION_FAILED", DiagnosticSeverity.WARNING,
                    document.metadata().documentId(), locator(0), "PDF 表格提取失败，未将不可靠内容并入正文"));
            return List.of();
        }
    }
}
