package com.hirain.aiagent.rag.indexer.model;

/** 跨格式统一结构块类型；表格由后续 Task 2.2 细化为 TableBlock。 */
public enum BlockType {
    PARAGRAPH,
    HEADING,
    TABLE,
    CODE,
    WARNING
}
