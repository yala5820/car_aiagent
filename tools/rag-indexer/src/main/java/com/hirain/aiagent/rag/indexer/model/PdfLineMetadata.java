package com.hirain.aiagent.rag.indexer.model;

/** PDF 视觉行的几何与列事实；这些字段只描述解析结果，不承载 Parent/Child 状态。 */
public record PdfLineMetadata(int pageNumber, int columnIndex, int lineIndex, boolean fullWidth) {
    public PdfLineMetadata {
        if (pageNumber < 1 || columnIndex < -1 || columnIndex > 1 || lineIndex < 0) {
            throw new IllegalArgumentException("PDF_LINE_METADATA_INVALID");
        }
    }
}
