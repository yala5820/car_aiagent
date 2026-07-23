package com.hirain.aiagent.rag.indexer.lexical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** 从 Child 生成 tf/df/length/avgdl；输入乱序时先按稳定 Child ID 归一。 */
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
        Map<String, Integer> lengths = new LinkedHashMap<>();
        Map<String, List<LexicalPosting>> terms = new TreeMap<>();
        for (LexicalDocument document : ordered) {
            List<String> tokens = analyzer.analyze(document.text());
            lengths.put(document.childId(), tokens.size());
            counter.count(tokens).forEach((term, tf) -> terms.computeIfAbsent(term, ignored -> new ArrayList<>())
                    .add(new LexicalPosting(document.childId(), tf)));
        }
        Map<String, List<LexicalPosting>> immutableTerms = new LinkedHashMap<>();
        terms.forEach((term, postings) -> immutableTerms.put(term, List.copyOf(postings)));
        double average = lengths.isEmpty() ? 0D : lengths.values().stream().mapToInt(Integer::intValue).average().orElseThrow();
        LexicalIndex index = new LexicalIndex(config.version(),
                Collections.unmodifiableMap(new LinkedHashMap<>(lengths)),
                Collections.unmodifiableMap(new LinkedHashMap<>(immutableTerms)), average);
        new LexicalIndexValidator().validate(index);
        return index;
    }
}
