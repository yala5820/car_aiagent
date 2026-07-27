package com.hirain.aiagent.rag.indexer.parser.pdf;

/** PDF 目录中的结构条目；目录页只提供层级参考，不直接作为正文证据。 */
record PdfTocEntry(String title, int level, int displayedPage, int tocPhysicalPage, int ordinal) {
    PdfTocEntry {
        if (title == null || title.isBlank() || level < 1 || level > 6 || displayedPage < 0
                || tocPhysicalPage < 1 || ordinal < 0) {
            throw new IllegalArgumentException("PDF_TOC_ENTRY_INVALID");
        }
    }
}
