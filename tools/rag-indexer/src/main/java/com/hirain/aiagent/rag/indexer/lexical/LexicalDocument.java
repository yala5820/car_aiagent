package com.hirain.aiagent.rag.indexer.lexical;

/** 只允许可检索 Child 进入词法索引；Parent 不能伪装为文档参与 postings。 */
public record LexicalDocument(String childId, String text) {
    public LexicalDocument {
        if (childId == null || childId.isBlank()) throw new IllegalArgumentException("childId 不能为空");
    }
}
