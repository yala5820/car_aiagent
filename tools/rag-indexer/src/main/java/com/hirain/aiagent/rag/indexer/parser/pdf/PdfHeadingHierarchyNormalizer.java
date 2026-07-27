package com.hirain.aiagent.rag.indexer.parser.pdf;

/** 使用文档上下文补足漏检标题，但只在标题形态明确时提高召回率。 */
final class PdfHeadingHierarchyNormalizer {
    boolean likelyHeading(PdfTextLine line, PdfTextLine previous, PdfTextLine next,
                          float pageAverageFontSize, PdfTocReference toc, int physicalPage) {
        if (toc.match(line, physicalPage) != null) return true;
        String text = line.text().trim();
        if (text.isBlank() || text.length() > 60 || text.matches(".*[。！？.!?，,；;：:]$")) return false;
        float ratio = line.averageFontSize() / Math.max(0.1f, pageAverageFontSize);
        boolean sameColumnBefore = previous == null || previous.columnIndex() == line.columnIndex();
        boolean sameColumnAfter = next == null || next.columnIndex() == line.columnIndex();
        boolean isolated = sameColumnBefore && sameColumnAfter
                && (previous == null || line.boundingBox().top() - previous.boundingBox().bottom() > line.averageFontSize() * 1.8f)
                && (next == null || next.boundingBox().top() - line.boundingBox().bottom() > line.averageFontSize() * 1.8f);
        return isolated && (line.fullWidth() && ratio >= 1.15f || ratio >= 1.30f);
    }

    int normalizeLevel(int resolvedLevel, PdfTocEntry tocEntry) {
        if (tocEntry != null) return tocEntry.level();
        return resolvedLevel > 0 ? resolvedLevel : 2;
    }
}
