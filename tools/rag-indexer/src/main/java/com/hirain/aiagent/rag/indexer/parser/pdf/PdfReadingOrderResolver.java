package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * V1 使用页内 y→x 的确定性阅读顺序。复杂多栏布局不做猜测：后续质量门禁可据 LOW 诊断拒绝发布。
 */
public final class PdfReadingOrderResolver {
    private static final float LINE_TOLERANCE = 4.0f;
    private static final float COLUMN_GAP_THRESHOLD = 120.0f;

    List<PdfTextLine> resolve(List<PdfGlyph> glyphs) {
        float maxRight = glyphs.stream().map(g -> g.x() + g.width()).max(Float::compare).orElse(0.0f);
        return resolve(glyphs, maxRight);
    }

    List<PdfTextLine> resolve(List<PdfGlyph> glyphs, float pageWidth) {
        List<List<PdfGlyph>> rows = sourceGroupedRows(glyphs);
        List<PdfTextLine> lines = new ArrayList<>();
        for (List<PdfGlyph> row : rows) {
            row.sort(Comparator.comparing(PdfGlyph::x));
            for (List<PdfGlyph> columnRow : splitAtPageDivider(row, pageWidth)) {
                List<PdfGlyph> segment = new ArrayList<>();
                for (PdfGlyph glyph : columnRow) {
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
        }
        return resolveColumns(lines, pageWidth);
    }

    private List<List<PdfGlyph>> sourceGroupedRows(List<PdfGlyph> glyphs) {
        boolean hasSourceGroups = glyphs.stream().anyMatch(glyph -> glyph.sourceGroup() > 0);
        if (hasSourceGroups) {
            Map<Integer, List<PdfGlyph>> grouped = new java.util.LinkedHashMap<>();
            glyphs.stream().sorted(Comparator.comparing(PdfGlyph::y).thenComparing(PdfGlyph::x))
                    .forEach(glyph -> grouped.computeIfAbsent(glyph.sourceGroup(), ignored -> new ArrayList<>()).add(glyph));
            List<List<PdfGlyph>> result = new ArrayList<>();
            grouped.values().forEach(value -> result.add(new ArrayList<>(value)));
            return result;
        }
        List<PdfGlyph> ordered = glyphs.stream().sorted(Comparator.comparing(PdfGlyph::y).thenComparing(PdfGlyph::x)).toList();
        List<List<PdfGlyph>> rows = new ArrayList<>();
        for (PdfGlyph glyph : ordered) {
            if (rows.isEmpty() || Math.abs(rows.get(rows.size() - 1).get(0).y() - glyph.y()) > LINE_TOLERANCE) rows.add(new ArrayList<>());
            rows.get(rows.size() - 1).add(glyph);
        }
        return rows;
    }

    /**
     * 目录类 PDF 的两列间距可能小于字符间距阈值，因此补充页面中线切分。
     * 只有中线两侧存在明确空白时才切分，避免把跨栏标题在中间截断。
     */
    private List<List<PdfGlyph>> splitAtPageDivider(List<PdfGlyph> row, float pageWidth) {
        if (pageWidth <= 0 || row.size() < 2) return List.of(row);
        float divider = pageWidth / 2.0f;
        for (int i = 1; i < row.size(); i++) {
            PdfGlyph previous = row.get(i - 1);
            PdfGlyph current = row.get(i);
            float previousRight = previous.x() + previous.width();
            float gap = current.x() - previousRight;
            // 目录点线可能刚好延伸到中线附近；允许在中线两侧 4pt 的窄空档处分栏。
            if (previousRight <= divider - 4.0f && current.x() >= divider - 4.0f && gap >= 1.5f) {
                return List.of(List.copyOf(row.subList(0, i)), List.copyOf(row.subList(i, row.size())));
            }
        }
        return List.of(row);
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

    private List<PdfTextLine> resolveColumns(List<PdfTextLine> lines, float pageWidth) {
        PdfColumnLayout layout = PdfColumnLayout.infer(pageWidth, lines);
        List<PdfTextLine> annotated = new ArrayList<>();
        int[] columnLines = new int[]{0, 0};
        for (PdfTextLine line : lines) {
            int column = layout.classify(line.boundingBox());
            int lineIndex = column >= 0 ? columnLines[column]++ : 0;
            annotated.add(new PdfTextLine(line.text(), line.boundingBox(), line.averageFontSize(), column,
                    column < 0, lineIndex));
        }
        return annotated.stream().sorted(Comparator
                .comparingInt((PdfTextLine line) -> line.fullWidth() ? -1 : line.columnIndex())
                .thenComparing(PdfTextLine::lineIndex)
                .thenComparing(line -> line.boundingBox().top())
                .thenComparing(line -> line.boundingBox().left()))
                .toList();
    }
}
