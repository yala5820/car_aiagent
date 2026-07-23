package com.hirain.aiagent.rag.indexer.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

/** 仅分类，不生成 Chunk 或 Store；只接受无需密码且 PDF 声明允许内容提取的加密文件。 */
public final class PdfDocumentClassifier {
    public PdfClassification classify(Path file) {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            // 加密标志本身不代表不可读。只要无需密码、当前权限允许提取，即可保持只读解析；绝不修改、解密或规避权限。
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                return PdfClassification.ENCRYPTED;
            }
            boolean hasTextPage = false;
            boolean hasEmptyPage = false;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (stripper.getText(document).trim().isEmpty()) {
                    hasEmptyPage = true;
                } else {
                    hasTextPage = true;
                }
            }
            if (hasTextPage && hasEmptyPage) {
                return PdfClassification.MIXED;
            }
            return hasTextPage ? PdfClassification.TEXT : PdfClassification.SCANNED_OR_EMPTY;
        } catch (InvalidPasswordException ignored) {
            return PdfClassification.ENCRYPTED;
        } catch (Exception ignored) {
            return PdfClassification.INVALID;
        }
    }
}
