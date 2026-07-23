package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.util.List;

/** 单物理页布局恢复结果；页码固定从 1 开始。 */
record PdfPageLayout(int physicalPage, List<PdfTextLine> lines) {
    PdfPageLayout {
        lines = List.copyOf(lines);
    }
}
