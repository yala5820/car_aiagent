package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅把跨页重复的首/尾行判为候选页眉页脚；宁可保留正文也不凭位置单独删除文本。 */
final class PdfHeaderFooterDetector {
    Set<String> detectRepeatedEdgeLines(List<PdfPageLayout> pages) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (PdfPageLayout page : pages) {
            if (!page.lines().isEmpty()) {
                count(occurrences, page.lines().get(0).text());
                if (page.lines().size() > 1) {
                    count(occurrences, page.lines().get(page.lines().size() - 1).text());
                }
            }
        }
        Set<String> repeated = new HashSet<>();
        occurrences.forEach((text, count) -> {
            if (count >= 2) {
                repeated.add(text);
            }
        });
        return Set.copyOf(repeated);
    }

    private void count(Map<String, Integer> occurrences, String text) {
        occurrences.merge(text.replaceAll("\\s+", " ").trim(), 1, Integer::sum);
    }
}
