package com.hirain.aiagent.rag.indexer.model;

import java.nio.file.Path;

/** 已通过路径与资源前置校验的 Parser 输入。 */
public record SourceDocument(Path path, SourceFormat sourceFormat, DocumentMetadata metadata) {
    public SourceDocument {
        if (path == null || sourceFormat == null || metadata == null) {
            throw new IllegalArgumentException("SourceDocument 字段不能为空");
        }
    }
}
