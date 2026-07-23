package com.hirain.aiagent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * 三格式统一来源定位。0 只表示“不适用”，绝不代表第 0 页或第 0 行。
 */
public final class SourceLocator {
    private final SourceFormat sourceFormat;
    private final List<String> headingPath;
    private final int pdfPageStart;
    private final int pdfPageEnd;
    private final String printedPageStartLabel;
    private final String printedPageEndLabel;
    private final String htmlElementId;
    private final int sourceLineStart;
    private final int sourceLineEnd;
    private final int sectionOrdinal;

    public SourceLocator(SourceFormat sourceFormat, List<String> headingPath, int pdfPageStart, int pdfPageEnd,
                         String printedPageStartLabel, String printedPageEndLabel, String htmlElementId,
                         int sourceLineStart, int sourceLineEnd, int sectionOrdinal) {
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat");
        this.headingPath = List.copyOf(headingPath == null ? List.of() : headingPath);
        this.pdfPageStart = pdfPageStart; this.pdfPageEnd = pdfPageEnd;
        this.printedPageStartLabel = emptyToNull(printedPageStartLabel); this.printedPageEndLabel = emptyToNull(printedPageEndLabel);
        this.htmlElementId = emptyToNull(htmlElementId); this.sourceLineStart = sourceLineStart; this.sourceLineEnd = sourceLineEnd;
        this.sectionOrdinal = sectionOrdinal;
        validate();
    }
    private void validate() {
        if (sectionOrdinal < 0) throw new IllegalArgumentException("sectionOrdinal 不能为负数");
        if (sourceFormat == SourceFormat.PDF) {
            if (pdfPageStart < 1 || pdfPageEnd < pdfPageStart || htmlElementId != null || sourceLineStart != 0 || sourceLineEnd != 0) throw new IllegalArgumentException("PDF Locator 字段非法");
        } else if (sourceFormat == SourceFormat.STATIC_HTML) {
            if (pdfPageStart != 0 || pdfPageEnd != 0 || printedPageStartLabel != null || printedPageEndLabel != null || sourceLineStart != 0 || sourceLineEnd != 0) throw new IllegalArgumentException("HTML Locator 字段非法");
        } else if (pdfPageStart != 0 || pdfPageEnd != 0 || printedPageStartLabel != null || printedPageEndLabel != null || htmlElementId != null || (sourceLineStart == 0) != (sourceLineEnd == 0) || (sourceLineStart != 0 && (sourceLineStart < 1 || sourceLineEnd < sourceLineStart))) {
            throw new IllegalArgumentException("Markdown Locator 字段非法");
        }
    }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    public SourceFormat sourceFormat() { return sourceFormat; } public List<String> headingPath() { return headingPath; }
    public int pdfPageStart() { return pdfPageStart; } public int pdfPageEnd() { return pdfPageEnd; }
    public String printedPageStartLabel() { return printedPageStartLabel; } public String printedPageEndLabel() { return printedPageEndLabel; }
    public String htmlElementId() { return htmlElementId; } public int sourceLineStart() { return sourceLineStart; } public int sourceLineEnd() { return sourceLineEnd; }
    public int sectionOrdinal() { return sectionOrdinal; }
}
