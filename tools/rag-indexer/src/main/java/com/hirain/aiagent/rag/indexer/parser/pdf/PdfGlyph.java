package com.hirain.aiagent.rag.indexer.parser.pdf;

/** PDFBox 提供的最小字形事实，坐标均为页面方向调整后的值，供布局恢复而非直接面向用户显示。 */
record PdfGlyph(String unicode, float x, float y, float width, float height, float fontSize, int sourceGroup) {
    PdfGlyph(String unicode, float x, float y, float width, float height, float fontSize) {
        this(unicode, x, y, width, height, fontSize, 0);
    }
}
