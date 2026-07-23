package com.hirain.aiagent.rag.indexer.model;

/** 文档解析所需的最小已审核元数据，不含机器绝对路径或隐私内容。 */
public record DocumentMetadata(String documentId, String title, String language) {
    public DocumentMetadata {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
    }
}
