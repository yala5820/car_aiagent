package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class TabulaPdfTableExtractorTest {
    @TempDir
    Path directory;

    @Test
    void shouldExtractTextSpacedTableWithStreamStrategy() throws Exception {
        Path file = directory.resolve("table.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                write(stream, "Item", 40, 700);
                write(stream, "Capacity", 240, 700);
                write(stream, "BrakeFluid", 40, 675);
                write(stream, "3L", 240, 675);
            }
            document.save(file.toFile());
        }

        var tables = new TabulaPdfTableExtractor().extract(file, PdfTableStrategy.STREAM);

        assertFalse(tables.isEmpty());
        assertEquals(2, tables.get(0).headers().size());
        assertFalse(new PdfDocumentParser().parse(new SourceDocument(file, SourceFormat.PDF,
                new DocumentMetadata("table-manual", "table", "en"))).tables().isEmpty());
        assertEquals(1, tables.get(0).rows().size());
    }

    @Test
    void shouldExtractRuledTableWithLatticeStrategyAndExposeItFromParser() throws Exception {
        Path file = directory.resolve("ruled-table.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                drawGrid(stream);
                write(stream, "Item", 50, 700);
                write(stream, "Value", 170, 700);
                write(stream, "Oil", 50, 670);
                write(stream, "3L", 170, 670);
            }
            document.save(file.toFile());
        }

        var tables = new TabulaPdfTableExtractor().extract(file, PdfTableStrategy.LATTICE);

        assertFalse(tables.isEmpty());
        assertEquals(2, tables.get(0).headers().size());
    }

    private void write(PDPageContentStream stream, String text, float x, float y) throws Exception {
        stream.beginText();
        stream.setFont(PDType1Font.HELVETICA, 12);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private void drawGrid(PDPageContentStream stream) throws Exception {
        float[] y = {720, 690, 660};
        for (float value : y) {
            stream.moveTo(40, value);
            stream.lineTo(260, value);
        }
        float[] x = {40, 150, 260};
        for (float value : x) {
            stream.moveTo(value, 720);
            stream.lineTo(value, 660);
        }
        stream.stroke();
    }
}
