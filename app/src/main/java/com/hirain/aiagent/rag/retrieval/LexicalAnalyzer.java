package com.hirain.aiagent.rag.retrieval;

import java.util.List;

/** 只负责 V1 Token 化；posting、BM25 与 Store 查询在后续阶段分离实现。 */
public interface LexicalAnalyzer { List<String> analyze(String text); }
