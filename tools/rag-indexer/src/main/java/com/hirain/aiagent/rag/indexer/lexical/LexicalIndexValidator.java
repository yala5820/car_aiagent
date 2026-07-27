package com.hirain.aiagent.rag.indexer.lexical;

import java.util.HashSet;
import java.util.Map;

/** 在写 Store 前阻断无效引用、重复 posting 与与文档长度不一致的 tf。 */
public final class LexicalIndexValidator {
    public void validate(LexicalIndex index) {
        if (index.averageTitleLength() < 0 || !Double.isFinite(index.averageTitleLength())
                || index.averageBodyLength() < 0 || !Double.isFinite(index.averageBodyLength())) throw new IllegalArgumentException("字段 avgdl 非法");
        validateField(index.titleLengths(), index.titlePostingsByTerm());
        validateField(index.bodyLengths(), index.bodyPostingsByTerm());
    }

    private static void validateField(Map<String, Integer> lengths, Map<String, java.util.List<LexicalPosting>> postingsByTerm) {
        for (Map.Entry<String, java.util.List<LexicalPosting>> entry : postingsByTerm.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().isEmpty()) throw new IllegalArgumentException("term/postings 非法");
            HashSet<String> childIds = new HashSet<>();
            String previous = null;
            for (LexicalPosting posting : entry.getValue()) {
                if (!lengths.containsKey(posting.childId()) || !childIds.add(posting.childId())) throw new IllegalArgumentException("posting Child 引用非法");
                if (previous != null && previous.compareTo(posting.childId()) >= 0) throw new IllegalArgumentException("postings 未按 Child ID 排序");
                previous = posting.childId();
            }
        }
    }
}
