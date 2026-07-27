package com.hirain.aiagent.rag.indexer.parser.pdf;

/** 只从明确编号或显著字号差异推断 PDF 标题层级；不可靠时返回 0。 */
final class PdfHeadingLevelResolver {
    int resolve(PdfTextLine line, float pageAverageFontSize) {
        String text=line.text().trim();
        java.util.regex.Matcher numbered=java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)\\s+.+").matcher(text);
        if(numbered.matches()) return Math.min(6, numbered.group(1).split("\\.").length);
        if(text.matches("^[一二三四五六七八九十]+、\\s*.+")) return 1;
        float ratio=line.averageFontSize()/Math.max(0.1f,pageAverageFontSize);
        if(ratio>=1.55f) return 1;
        if(ratio>=1.32f) return 2;
        return 0;
    }

    int resolve(PdfTextLine line, float pageAverageFontSize, PdfTocEntry tocEntry) {
        return tocEntry == null ? resolve(line, pageAverageFontSize) : tocEntry.level();
    }
}
