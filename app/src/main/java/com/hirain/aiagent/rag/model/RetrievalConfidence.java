package com.hirain.aiagent.rag.model;

/** 检索相关性置信度等级。阈值尚未经过人工评测校准前统一为 UNASSESSED。 */
public enum RetrievalConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNASSESSED
}
