package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** PDF 布局分析的单一入口，后续可在不改变 Parser 契约的前提下替换坐标恢复策略。 */
final class PdfLayoutAnalyzer {
    private final PdfGlyphExtractor glyphExtractor = new PdfGlyphExtractor();

    List<PdfPageLayout> analyze(Path file) throws IOException {
        return glyphExtractor.extractLayouts(file);
    }
}
