package com.hirain.aiagent.rag.indexer.lexical;

/** 只允许可检索 Child 进入词法索引；标题与正文保持独立字段。 */
public record LexicalDocument(String childId, String title, String body) {
    public LexicalDocument {
        if (childId == null || childId.isBlank()) throw new IllegalArgumentException("childId 不能为空");
        title = title == null ? "" : title;
        body = body == null ? "" : body;
    }

    /** 兼容旧调用方，旧 text 语义视为 BODY。 */
    public LexicalDocument(String childId, String text) { this(childId, "", text); }
}
