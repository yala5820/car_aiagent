package com.hirain.aiagent.rag.indexer.parser.html;

import com.hirain.aiagent.rag.indexer.model.DiagnosticSeverity;
import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.ParseDiagnostic;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import com.hirain.aiagent.rag.indexer.parser.table.TableNormalizer;
import com.hirain.aiagent.rag.indexer.parser.table.TableValidator;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** DOM 表格直接转为统一结构；合并单元格在 V1 不做臆测展开，必须给出稳定诊断。 */
final class HtmlTableExtractor {
    private final TableNormalizer normalizer = new TableNormalizer();
    private final TableValidator validator = new TableValidator();

    HtmlTableExtraction extract(Element root, String documentId, HtmlSourceLocatorFactory locators, String headingPath, int ordinal) {
        List<TableBlock> tables = new ArrayList<>();
        List<ParseDiagnostic> diagnostics = new ArrayList<>();
        int tableOrdinal = ordinal;
        for (Element table : root.select("table")) {
            if (table.select("[rowspan],[colspan]").size() > 0) {
                diagnostics.add(diagnostic("HTML_TABLE_SPAN_UNSUPPORTED", documentId, locators.create(table, headingPath, ++tableOrdinal), "HTML 表格含合并单元格，V1 禁止猜测展开"));
                continue;
            }
            List<List<String>> rows = normalizer.normalize(table.select("tr").stream()
                    .map(row -> row.select("> th, > td").stream().map(Element::text).toList()).toList());
            if (rows.size() < 2) {
                diagnostics.add(diagnostic("HTML_TABLE_INSUFFICIENT_ROWS", documentId, locators.create(table, headingPath, ++tableOrdinal), "HTML 表格不足以形成表头和数据行"));
                continue;
            }
            String reason = validator.validate(rows.get(0), rows.subList(1, rows.size()));
            if (reason != null) {
                diagnostics.add(diagnostic(reason, documentId, locators.create(table, headingPath, ++tableOrdinal), "HTML 表格结构无效"));
                continue;
            }
            String title = table.attr("aria-label");
            tables.add(new TableBlock(title, rows.get(0), rows.subList(1, rows.size()), locators.create(table, headingPath, ++tableOrdinal),
                    TableExtractionMode.DOM, ExtractionConfidence.HIGH));
        }
        return new HtmlTableExtraction(tables, diagnostics);
    }

    private ParseDiagnostic diagnostic(String reason, String documentId, com.hirain.aiagent.rag.indexer.model.SourceLocator locator, String summary) {
        return new ParseDiagnostic(reason, DiagnosticSeverity.WARNING, documentId, locator, summary);
    }
}
