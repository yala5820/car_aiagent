package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 逐页读取字形坐标、尺寸与字体信息；禁止将一次 getText() 作为布局恢复结果。 */
public final class PdfGlyphExtractor {
    public List<PdfPageAnalysis> extract(Path file) throws Exception {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            List<PdfPageAnalysis> output = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                output.add(new PdfPageAnalysis(page, stripper.getText(document)));
            }
            return List.copyOf(output);
        }
    }

    public List<PdfPageLayout> extractLayouts(Path file) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            List<PdfPageLayout> output = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PositionCapturingStripper stripper = new PositionCapturingStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.getText(document);
                float pageWidth = document.getPage(page - 1).getCropBox().getWidth();
                List<PdfTextLine> lines = new PdfReadingOrderResolver().resolve(stripper.glyphs, pageWidth);
                output.add(new PdfPageLayout(page, pageWidth, PdfColumnLayout.infer(pageWidth, lines), lines));
            }
            return List.copyOf(output);
        }
    }

    private static final class PositionCapturingStripper extends PDFTextStripper {
        private final List<PdfGlyph> glyphs = new ArrayList<>();
        private int sourceGroup;

        private PositionCapturingStripper() throws IOException {
            setSortByPosition(false);
        }

        @Override
        protected void writeString(String ignoredText, List<TextPosition> positions) {
            int group = ++sourceGroup;
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode != null && !unicode.isBlank()) {
                    glyphs.add(new PdfGlyph(unicode, position.getXDirAdj(), position.getYDirAdj(),
                            position.getWidthDirAdj(), position.getHeightDir(), position.getFontSizeInPt(), group));
                }
            }
        }
    }
}
