package com.hirain.aiagent.rag.indexer.model;

/** 结构恢复可信度；V1 的 PDFBox 文本顺序为 MEDIUM，后续布局分析可提升或降低。 */
public enum ExtractionConfidence {
    HIGH,
    MEDIUM,
    LOW
}
