package com.hirain.aiagent.rag.indexer.parser.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 为本地人工审核复现 PDF Parser 的重复页眉/页脚判定。
 *
 * <p>该类只读取已授权原 PDF，返回被排除的原始行和物理页号；不修改 PDF、不访问网络，也不参与 Bundle 构建。</p>
 */
public final class PdfRepeatedEdgeInspector {
    public List<RepeatedEdgeLine> inspect(Path source) throws IOException {
        List<PdfPageLayout> layouts = new PdfLayoutAnalyzer().analyze(source);
        var excluded = new PdfHeaderFooterDetector().detectRepeatedEdgeLines(layouts);
        Map<String, TreeSet<Integer>> pagesByLine = new TreeMap<>();
        for (PdfPageLayout page : layouts) {
            for (PdfTextLine line : page.lines()) {
                String normalized = PdfBlockAssembler.normalize(line.text());
                if (excluded.contains(normalized)) {
                    pagesByLine.computeIfAbsent(normalized, ignored -> new TreeSet<>()).add(page.physicalPage());
                }
            }
        }
        List<RepeatedEdgeLine> result = new ArrayList<>();
        pagesByLine.forEach((text, pages) -> result.add(new RepeatedEdgeLine(text, List.copyOf(pages))));
        return List.copyOf(result);
    }

    /** 单个被排除的重复行及其所有物理页，供本地审核页面折叠展示。 */
    public record RepeatedEdgeLine(String text, List<Integer> physicalPages) { }
}
