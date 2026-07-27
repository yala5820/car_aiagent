package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

/** 目录参考索引；只用于正文标题层级校正和目录页排除。 */
record PdfTocReference(List<PdfTocEntry> entries, Set<Integer> tocPages, int pageOffset) {
    PdfTocReference {
        entries = List.copyOf(entries);
        tocPages = Set.copyOf(tocPages);
    }

    boolean isTocPage(int physicalPage) {
        return tocPages.contains(physicalPage);
    }

    PdfTocEntry match(PdfTextLine line, int physicalPage) {
        String normalized = normalize(line.text());
        if (normalized.isBlank()) return null;
        PdfTocEntry best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (PdfTocEntry entry : entries) {
            if (!normalize(entry.title()).equals(normalized)) continue;
            int expectedPage = entry.displayedPage() <= 0 ? 0 : entry.displayedPage() + pageOffset;
            int distance = expectedPage <= 0 ? 0 : Math.abs(expectedPage - physicalPage);
            if (distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .replaceAll("[.。·…]+\\s*\\d+$", "")
                .trim();
    }
}
