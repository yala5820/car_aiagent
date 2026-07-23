package com.hirain.aiagent.rag.indexer.lexical;

import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** 使用字典序 Map 固化 term 顺序，确保重建得到可比较的 postings。 */
public final class TermFrequencyCounter {
    public Map<String, Integer> count(Iterable<String> terms) {
        Map<String, Integer> result = new TreeMap<>();
        for (String term : terms) result.merge(term, 1, Integer::sum);
        return Collections.unmodifiableMap(new TreeMap<>(result));
    }
}
