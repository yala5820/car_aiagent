package com.hirain.aiagent.rag.model;
/** RAG 总体结果状态，不能由单个检索阶段的局部成功替代。 */
public enum RagStatus { SUCCESS, NO_EVIDENCE, DEGRADED, TIMEOUT, CANCELLED, ERROR }
