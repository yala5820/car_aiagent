package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;
import java.util.List;

/** 单页最多两列的确定性布局模型；跨栏行使用 columnIndex=-1。 */
record PdfColumnLayout(float pageWidth, float divider, boolean twoColumns) {
    static PdfColumnLayout infer(float pageWidth, List<PdfTextLine> lines) {
        if (pageWidth <= 0 || lines.size() < 3) return new PdfColumnLayout(pageWidth, pageWidth / 2.0f, false);
        long bodyLines = lines.stream().filter(line -> line.boundingBox().right() - line.boundingBox().left() < pageWidth * 0.78f).count();
        if (bodyLines < 3) return new PdfColumnLayout(pageWidth, pageWidth / 2.0f, false);
        float divider = pageWidth / 2.0f;
        List<Float> starts = lines.stream()
                .filter(line -> line.boundingBox().right() - line.boundingBox().left() < pageWidth * 0.78f)
                .map(line -> line.boundingBox().left()).sorted().toList();
        boolean bothSides = starts.stream().anyMatch(value -> value < pageWidth / 2.0f)
                && starts.stream().anyMatch(value -> value >= pageWidth / 2.0f);
        return new PdfColumnLayout(pageWidth, divider, bothSides && starts.size() >= 3);
    }

    int classify(BoundingBox box) {
        if (!twoColumns) return -1;
        float width = box.right() - box.left();
        if (width >= pageWidth * 0.78f || (box.left() < divider - 15.0f && box.right() > divider + 15.0f)) return -1;
        return (box.left() + box.right()) / 2.0f <= divider ? 0 : 1;
    }
}
