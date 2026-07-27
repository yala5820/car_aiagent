package com.hirain.aiagent.rag.indexer.lexical;

import java.util.List;
import java.util.Map;

/** 离线到 Store 映射之前的不可变字段化词法中间模型。 */
public record LexicalIndex(
        String analyzerVersion,
        Map<String, Integer> titleLengths,
        Map<String, Integer> bodyLengths,
        Map<String, List<LexicalPosting>> titlePostingsByTerm,
        Map<String, List<LexicalPosting>> bodyPostingsByTerm,
        double averageTitleLength,
    double averageBodyLength) {
    public LexicalIndex {
        titleLengths = orderedImmutable(titleLengths);
        bodyLengths = orderedImmutable(bodyLengths);
        titlePostingsByTerm = immutable(titlePostingsByTerm);
        bodyPostingsByTerm = immutable(bodyPostingsByTerm);
    }

    public LexicalIndex(String analyzerVersion, Map<String, Integer> documentLengths,
                        Map<String, List<LexicalPosting>> postingsByTerm, double averageDocumentLength) {
        this(analyzerVersion, Map.of(), documentLengths, Map.of(), postingsByTerm, 0D, averageDocumentLength);
    }

    /** 兼容旧代码：默认字段为 BODY。 */
    public Map<String, Integer> documentLengths() { return bodyLengths; }
    public Map<String, List<LexicalPosting>> postingsByTerm() { return bodyPostingsByTerm; }
    public double averageDocumentLength() { return averageBodyLength; }
    public int documentFrequency(String term) { return bodyPostingsByTerm.getOrDefault(term, List.of()).size(); }

    private static Map<String, List<LexicalPosting>> immutable(Map<String, List<LexicalPosting>> source) {
        Map<String, List<LexicalPosting>> result = new java.util.LinkedHashMap<>();
        source.forEach((term, postings) -> result.put(term, List.copyOf(postings)));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** 保留构建器已经确定的 Child ID 顺序，避免 Map.copyOf 的实现细节破坏可复现产物。 */
    private static <K, V> Map<K, V> orderedImmutable(Map<K, V> source) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(source));
    }
}
