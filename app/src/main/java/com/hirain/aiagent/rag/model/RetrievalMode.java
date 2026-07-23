package com.hirain.aiagent.rag.model;
/** 结果使用的检索路径；仅内部诊断使用，不能进入模型 ToolResult。 */
public enum RetrievalMode { HYBRID_RERANKED, HYBRID_FUSION_ONLY, LEXICAL_ONLY }
