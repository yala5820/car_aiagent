package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * V1 使用页内 y→x 的确定性阅读顺序。复杂多栏布局不做猜测：后续质量门禁可据 LOW 诊断拒绝发布。
 */
public final class PdfReadingOrderResolver {
    private static final float LINE_TOLERANCE = 4.0f;
    private static final float COLUMN_GAP_THRESHOLD = 120.0f;

    List<PdfTextLine> resolve(List<PdfGlyph> glyphs) {
        List<PdfGlyph> ordered = glyphs.stream()
                .sorted(Comparator.comparing(PdfGlyph::y).thenComparing(PdfGlyph::x))
                .toList();
        List<List<PdfGlyph>> rows = new ArrayList<>();
        for (PdfGlyph glyph : ordered) {
            if (rows.isEmpty() || Math.abs(rows.get(rows.size() - 1).get(0).y() - glyph.y()) > LINE_TOLERANCE) {
                rows.add(new ArrayList<>());
            }
            rows.get(rows.size() - 1).add(glyph);
        }
        List<PdfTextLine> lines = new ArrayList<>();
        for (List<PdfGlyph> row : rows) {
            row.sort(Comparator.comparing(PdfGlyph::x));
            List<PdfGlyph> segment = new ArrayList<>();
            for (PdfGlyph glyph : row) {
                if (!segment.isEmpty()) {
                    PdfGlyph previous = segment.get(segment.size() - 1);
                    float gap = glyph.x() - (previous.x() + previous.width());
                    if (gap > Math.max(15.0f, previous.width() * 4.0f)) {
                        appendSegment(lines, segment);
                        segment = new ArrayList<>();
                    }
                }
                segment.add(glyph);
            }
            appendSegment(lines, segment);
        }
        return resolveColumns(lines);
    }

    private void appendSegment(List<PdfTextLine> lines, List<PdfGlyph> row) {
        if (row.isEmpty()) {
            return;
        }
            StringBuilder text = new StringBuilder();
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = Float.MIN_VALUE, bottom = Float.MIN_VALUE, fontTotal = 0;
            for (PdfGlyph glyph : row) {
                text.append(glyph.unicode());
                left = Math.min(left, glyph.x());
                top = Math.min(top, glyph.y());
                right = Math.max(right, glyph.x() + glyph.width());
                bottom = Math.max(bottom, glyph.y() + glyph.height());
                fontTotal += glyph.fontSize();
            }
            String normalized = text.toString().trim();
            if (!normalized.isEmpty()) {
                lines.add(new PdfTextLine(normalized, new BoundingBox(left, top, right, bottom), fontTotal / row.size()));
            }
    }

    private List<PdfTextLine> resolveColumns(List<PdfTextLine> lines) {
        if (lines.size() < 3) {
            return List.copyOf(lines);
        }
        List<Float> starts = lines.stream().map(line -> line.boundingBox().left()).sorted().toList();
        float largestGap = 0;
        float divider = 0;
        for (int index = 1; index < starts.size(); index++) {
            float gap = starts.get(index) - starts.get(index - 1);
            if (gap > largestGap) {
                largestGap = gap;
                divider = (starts.get(index) + starts.get(index - 1)) / 2.0f;
            }
        }
        if (largestGap < COLUMN_GAP_THRESHOLD) {
            return List.copyOf(lines);
        }
        final float columnDivider = divider;
        return lines.stream().sorted(Comparator
                .comparingInt((PdfTextLine line) -> line.boundingBox().left() < columnDivider ? 0 : 1)
                .thenComparing(line -> line.boundingBox().top())
                .thenComparing(line -> line.boundingBox().left()))
                .toList();
    }
}
