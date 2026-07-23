package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.ExtractionConfidence;
import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import com.hirain.aiagent.rag.indexer.model.SourceLocator;
import com.hirain.aiagent.rag.indexer.model.TableBlock;
import com.hirain.aiagent.rag.indexer.model.TableExtractionMode;
import com.hirain.aiagent.rag.indexer.parser.table.TableNormalizer;
import com.hirain.aiagent.rag.indexer.parser.table.TableValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** 仅使用 Tabula 的文本表格能力；不触发 OCR、页面渲染或外部资源访问。 */
final class TabulaPdfTableExtractor implements PdfTableExtractor {
    private final TableNormalizer normalizer = new TableNormalizer();
    private final TableValidator validator = new TableValidator();

    @Override
    public List<TableBlock> extract(Path file, IntFunction<PdfTableStrategy> strategyForPage) throws Exception {
        try (PDDocument document = PDDocument.load(file.toFile()); ObjectExtractor extractor = new ObjectExtractor(document)) {
            List<TableBlock> output = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                Page page = extractor.extract(pageNumber);
                PdfTableStrategy strategy = strategyForPage.apply(pageNumber);
                List<Table> tables = extractPage(page, strategy);
                for (Table table : tables) {
                    List<List<String>> rows = normalizer.normalize(toTextRows(table));
                    if (rows.size() < 2) {
                        continue;
                    }
                    List<String> headers = rows.get(0);
                    List<List<String>> body = rows.subList(1, rows.size());
                    String invalidReason = validator.validate(headers, body);
                    if (invalidReason != null) {
                        throw new IllegalArgumentException(invalidReason);
                    }
                    output.add(new TableBlock("", headers, body,
                            new SourceLocator(SourceFormat.PDF, pageNumber, pageNumber, null, 0, 0, null, pageNumber),
                            strategy == PdfTableStrategy.LATTICE ? TableExtractionMode.LATTICE : TableExtractionMode.STREAM,
                            ExtractionConfidence.MEDIUM,
                            new BoundingBox(table.getLeft(), table.getTop(), table.getRight(), table.getBottom())));
                }
            }
            return List.copyOf(output);
        }
    }

    private List<Table> extractPage(Page page, PdfTableStrategy strategy) {
        if (strategy == PdfTableStrategy.LATTICE) {
            return new SpreadsheetExtractionAlgorithm().extract(page);
        }
        if (strategy == PdfTableStrategy.STREAM) {
            return new BasicExtractionAlgorithm().extract(page);
        }
        List<Table> lattice = new SpreadsheetExtractionAlgorithm().extract(page);
        return lattice.isEmpty() ? new BasicExtractionAlgorithm().extract(page) : lattice;
    }

    private List<List<String>> toTextRows(Table table) {
        return table.getRows().stream().map(row -> row.stream()
                .map(RectangularTextContainer::getText).toList()).toList();
    }
}
