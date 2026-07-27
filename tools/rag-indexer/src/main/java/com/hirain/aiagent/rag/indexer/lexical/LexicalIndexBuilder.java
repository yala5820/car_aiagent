package com.hirain.aiagent.rag.indexer.lexical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

    /** 从 Child 的 TITLE/BODY 独立字段生成 tf/df/length/avgdl；输入乱序时先按稳定 Child ID 归一。 */
public final class LexicalIndexBuilder {
    private final LexicalAnalyzer analyzer;
    private final LexicalAnalyzerConfig config;
    private final TermFrequencyCounter counter = new TermFrequencyCounter();

    public LexicalIndexBuilder(LexicalAnalyzer analyzer, LexicalAnalyzerConfig config) {
        this.analyzer = analyzer;
        this.config = config;
    }

    public LexicalIndex build(List<LexicalDocument> documents) {
        List<LexicalDocument> ordered = documents.stream().sorted(Comparator.comparing(LexicalDocument::childId)).toList();
        Map<String, Integer> titleLengths = new LinkedHashMap<>();
        Map<String, Integer> bodyLengths = new LinkedHashMap<>();
        Map<String, List<LexicalPosting>> titleTerms = new TreeMap<>();
        Map<String, List<LexicalPosting>> bodyTerms = new TreeMap<>();
        for (LexicalDocument document : ordered) {
            List<String> titleTokens = analyzer.analyze(document.title());
            List<String> bodyTokens = analyzer.analyze(document.body());
            titleLengths.put(document.childId(), titleTokens.size());
            bodyLengths.put(document.childId(), bodyTokens.size());
            counter.count(titleTokens).forEach((term, tf) -> titleTerms.computeIfAbsent(term, ignored -> new ArrayList<>())
                    .add(new LexicalPosting(document.childId(), tf)));
            counter.count(bodyTokens).forEach((term, tf) -> bodyTerms.computeIfAbsent(term, ignored -> new ArrayList<>())
                    .add(new LexicalPosting(document.childId(), tf)));
        }
        double titleAverage = titleLengths.isEmpty() ? 0D : titleLengths.values().stream().mapToInt(Integer::intValue).average().orElseThrow();
        double bodyAverage = bodyLengths.isEmpty() ? 0D : bodyLengths.values().stream().mapToInt(Integer::intValue).average().orElseThrow();
        LexicalIndex index = new LexicalIndex(config.version(), titleLengths, bodyLengths, titleTerms, bodyTerms, titleAverage, bodyAverage);
        new LexicalIndexValidator().validate(index);
        return index;
    }
}
