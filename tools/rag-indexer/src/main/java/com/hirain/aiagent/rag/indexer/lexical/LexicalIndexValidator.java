package com.hirain.aiagent.rag.indexer.lexical;

import java.util.HashSet;
import java.util.Map;

/** 在写 Store 前阻断无效引用、重复 posting 与与文档长度不一致的 tf。 */
public final class LexicalIndexValidator {
    public void validate(LexicalIndex index) {
        if (index.averageDocumentLength() < 0 || !Double.isFinite(index.averageDocumentLength())) throw new IllegalArgumentException("avgdl 非法");
        for (Map.Entry<String, java.util.List<LexicalPosting>> entry : index.postingsByTerm().entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().isEmpty()) throw new IllegalArgumentException("term/postings 非法");
            HashSet<String> childIds = new HashSet<>();
            String previous = null;
            for (LexicalPosting posting : entry.getValue()) {
                if (!index.documentLengths().containsKey(posting.childId()) || !childIds.add(posting.childId())) throw new IllegalArgumentException("posting Child 引用非法");
                if (previous != null && previous.compareTo(posting.childId()) >= 0) throw new IllegalArgumentException("postings 未按 Child ID 排序");
                previous = posting.childId();
            }
        }
    }
}
