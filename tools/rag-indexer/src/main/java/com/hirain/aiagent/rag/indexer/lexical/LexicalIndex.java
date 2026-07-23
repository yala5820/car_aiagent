package com.hirain.aiagent.rag.indexer.lexical;

import java.util.List;
import java.util.Map;

/** 离线到 Store 映射之前的不可变词法中间模型。 */
public record LexicalIndex(
        String analyzerVersion,
        Map<String, Integer> documentLengths,
        Map<String, List<LexicalPosting>> postingsByTerm,
        double averageDocumentLength) {
    public int documentFrequency(String term) { return postingsByTerm.getOrDefault(term, List.of()).size(); }
}
