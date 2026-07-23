package com.hirain.aiagent.rag.indexer.lexical;

/** 一个 term 对一个 Child 的确定性 posting。 */
public record LexicalPosting(String childId, int termFrequency) {
    public LexicalPosting {
        if (childId == null || childId.isBlank() || termFrequency <= 0) throw new IllegalArgumentException("非法 posting");
    }
}
