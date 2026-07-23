package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hirain.aiagent.rag.indexer.model.DocumentMetadata;
import com.hirain.aiagent.rag.indexer.model.SourceDocument;
import com.hirain.aiagent.rag.indexer.model.SourceFormat;

final class PdfDocumentClassifierTest {
    @TempDir
    Path dir;

    @Test
    void shouldClassifyAndExtractTextPdf() throws Exception {
        Path file = dir.resolve("a.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(20, 700);
                stream.showText("brake fluid");
                stream.endText();
            }
            document.save(file.toFile());
        }

        assertEquals(PdfClassification.TEXT, new PdfDocumentClassifier().classify(file));
        assertEquals(1, new PdfGlyphExtractor().extract(file).size());
        assertTrue(new PdfGlyphExtractor().extract(file).get(0).text().contains("brake fluid"));
        assertTrue(new PdfDocumentParser().parse(new SourceDocument(file, SourceFormat.PDF,
                new DocumentMetadata("manual", "manual", "zh-CN"))).isSuccessful());
    }

    @Test
    void shouldRejectMixedPdfInsteadOfSilentlyDroppingAnEmptyPage() throws Exception {
        Path file = dir.resolve("mixed.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage textPage = new PDPage(PDRectangle.A4);
            document.addPage(textPage);
            document.addPage(new PDPage(PDRectangle.A4));
            try (PDPageContentStream stream = new PDPageContentStream(document, textPage)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(20, 700);
                stream.showText("text page");
                stream.endText();
            }
            document.save(file.toFile());
        }

        assertEquals(PdfClassification.MIXED, new PdfDocumentClassifier().classify(file));
        assertTrue(new PdfDocumentParser().parse(new SourceDocument(file, SourceFormat.PDF,
                new DocumentMetadata("manual", "manual", "zh-CN"))).diagnostics().get(0).reasonCode().equals("PDF_MIXED"));
    }

    @Test
    void shouldKeepEncryptedAndInvalidInputsDistinct() throws Exception {
        Path encrypted = dir.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            AccessPermission permission = new AccessPermission();
            document.protect(new StandardProtectionPolicy("owner", "user", permission));
            document.save(encrypted.toFile());
        }
        Path invalid = dir.resolve("invalid.pdf");
        Files.writeString(invalid, "not a PDF");

        assertEquals(PdfClassification.ENCRYPTED, new PdfDocumentClassifier().classify(encrypted));
        assertEquals(PdfClassification.INVALID, new PdfDocumentClassifier().classify(invalid));
    }

    @Test
    void shouldAcceptPasswordlessEncryptedPdfWhenExtractionIsAllowed() throws Exception {
        Path file = dir.resolve("extractable-encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(20, 700);
                stream.showText("read only extractable text");
                stream.endText();
            }
            AccessPermission permission = new AccessPermission();
            permission.setCanExtractContent(true);
            document.protect(new StandardProtectionPolicy("owner", "", permission));
            document.save(file.toFile());
        }

        assertEquals(PdfClassification.TEXT, new PdfDocumentClassifier().classify(file));
        assertTrue(new PdfDocumentParser().parse(new SourceDocument(file, SourceFormat.PDF,
                new DocumentMetadata("manual", "manual", "zh-CN"))).isSuccessful());
    }

    @Test
    void shouldDiagnoseButNeverExecutePdfOpenAction() throws Exception {
        Path file = dir.resolve("active-content.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(20, 700);
                stream.showText("safe text");
                stream.endText();
            }
            COSDictionary action = new COSDictionary();
            action.setName(COSName.S, "JavaScript");
            action.setString(COSName.JS, "app.alert('must not execute')");
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, action);
            document.save(file.toFile());
        }

        assertTrue(new PdfDocumentParser().parse(new SourceDocument(file, SourceFormat.PDF,
                new DocumentMetadata("manual", "manual", "zh-CN"))).diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.reasonCode().equals("PDF_IGNORED_ACTION_JAVASCRIPT")));
    }
}
