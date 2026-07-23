package com.hirain.aiagent.rag.indexer.parser.pdf;

/** PDF 表格策略；AUTO 由检测结果选择且应记录最终 extractionMode。 */
enum PdfTableStrategy {
    AUTO,
    LATTICE,
    STREAM
}
