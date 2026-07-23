package com.hirain.aiagent.rag.indexer.fixture;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** 仅在测试工作目录生成可追溯的最小 PDF，禁止把未知来源二进制混入 Fixture。 */
public final class PdfFixtureFactory {
    private PdfFixtureFactory() { }
    public static Path writeBrakeFluidFixture(Path output) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12); content.newLineAtOffset(72, 720);
                content.showText("Brake fluid: if level is low, contact service center."); content.endText();
            }
            document.save(output.toFile());
        }
        return output;
    }
}
