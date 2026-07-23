package com.hirain.aiagent.rag.indexer.parser.pdf;

/** 字号显著高于页面正文均值或具有常见编号前缀的行，作为保守的标题候选。 */
final class PdfHeadingDetector {
    private final float minimumScale;

    PdfHeadingDetector() {
        this(1.2f);
    }

    PdfHeadingDetector(float minimumScale) {
        this.minimumScale = minimumScale;
    }

    boolean isHeading(PdfTextLine line, float pageAverageFontSize) {
        return line.averageFontSize() >= pageAverageFontSize * minimumScale
                || line.text().matches("^(?:[0-9]+(?:\\.[0-9]+)*|[一二三四五六七八九十]+、)\\s*.+");
    }
}
