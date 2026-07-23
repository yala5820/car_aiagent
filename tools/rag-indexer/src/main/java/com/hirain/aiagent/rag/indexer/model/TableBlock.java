package com.hirain.aiagent.rag.indexer.model;

import java.util.List;

/**
 * 跨格式统一表格结构。表格不扁平化混入普通段落，后续 Chunk 必须使用渲染后的自包含文本。
 */
public record TableBlock(
        String title,
        List<String> headers,
        List<List<String>> rows,
        SourceLocator locator,
        TableExtractionMode extractionMode,
        ExtractionConfidence confidence,
        BoundingBox boundingBox
) {
    public TableBlock {
        title = title == null ? "" : title.trim();
        headers = List.copyOf(headers);
        rows = rows.stream().map(List::copyOf).toList();
        if (headers.isEmpty() || locator == null || extractionMode == null || confidence == null) {
            throw new IllegalArgumentException("TableBlock 缺少必填结构字段");
        }
    }

    public TableBlock(String title, List<String> headers, List<List<String>> rows, SourceLocator locator,
                      TableExtractionMode extractionMode, ExtractionConfidence confidence) {
        this(title, headers, rows, locator, extractionMode, confidence, null);
    }
}
