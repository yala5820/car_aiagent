package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从静态 PDF 目录页提取标题层级；无法确认的页码偏移保持为 0。 */
final class PdfTocReferenceExtractor {
    private static final Pattern ENTRY = Pattern.compile("^(.+?)(?:[.。·…]{2,}|\\s{2,})(\\d{1,4})$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\s+.+");
    private static final Pattern CHINESE_ROOT = Pattern.compile("^[一二三四五六七八九十百]+[、.]\\s*.+");
    private static final Pattern CHINESE_CHILD = Pattern.compile("^[（(][一二三四五六七八九十百]+[）)].+");

    PdfTocReference extract(List<PdfPageLayout> layouts) {
        Set<Integer> pages = new HashSet<>();
        List<PdfTocEntry> entries = new ArrayList<>();
        boolean previousWasToc = false;
        for (PdfPageLayout layout : layouts) {
            List<PdfTextLine> lines = layout.lines();
            long entryCount = lines.stream().filter(line -> ENTRY.matcher(line.text().trim()).matches()).count();
            boolean titlePage = lines.stream().anyMatch(line -> line.text().replaceAll("\\s+", "").matches(".*(目录|目次|Contents).*"));
            boolean continuation = previousWasToc && entryCount > 0;
            if (!titlePage && !continuation && entryCount < 3) {
                previousWasToc = false;
                continue;
            }
            if (entryCount < 1) {
                previousWasToc = false;
                continue;
            }
            pages.add(layout.physicalPage());
            previousWasToc = true;
            int ordinal = entries.size();
            for (PdfTextLine line : lines) {
                Matcher matcher = ENTRY.matcher(line.text().trim());
                if (!matcher.matches()) continue;
                String title = matcher.group(1).replaceAll("\\s+", " ").trim();
                int page;
                try { page = Integer.parseInt(matcher.group(2)); } catch (NumberFormatException ignored) { continue; }
                if (title.isBlank()) continue;
                entries.add(new PdfTocEntry(title, level(title, line, lines), page, layout.physicalPage(), ordinal++));
            }
        }
        entries.sort(Comparator.comparingInt(PdfTocEntry::displayedPage).thenComparingInt(PdfTocEntry::ordinal));
        return new PdfTocReference(entries, pages, 0);
    }

    private int level(String title, PdfTextLine line, List<PdfTextLine> lines) {
        Matcher numbered = NUMBERED.matcher(title);
        if (numbered.matches()) return Math.min(6, numbered.group(1).split("\\.").length);
        if (CHINESE_ROOT.matcher(title).matches()) return 1;
        if (CHINESE_CHILD.matcher(title).matches()) return 2;
        double minLeft = lines.stream().map(PdfTextLine::boundingBox).mapToDouble(box -> box.left()).min().orElse(line.boundingBox().left());
        return line.boundingBox().left() > minLeft + 18.0f ? 2 : 1;
    }
}
