package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.util.List;

/** 单物理页布局恢复结果；页码固定从 1 开始。 */
record PdfPageLayout(int physicalPage, float pageWidth, PdfColumnLayout columnLayout, List<PdfTextLine> lines) {
    PdfPageLayout(int physicalPage, List<PdfTextLine> lines) {
        this(physicalPage, 0.0f, PdfColumnLayout.infer(0.0f, lines), lines);
    }
    PdfPageLayout {
        lines = List.copyOf(lines);
    }
}
