package com.hirain.aiagent.rag.indexer.model;

/** 表格的实际提取方式必须随结果记录，禁止把自动策略选择隐藏在 Parser 内部。 */
public enum TableExtractionMode {
    LATTICE,
    STREAM,
    DOM,
    MARKDOWN
}
