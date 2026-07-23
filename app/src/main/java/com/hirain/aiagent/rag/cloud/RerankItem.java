package com.hirain.aiagent.rag.cloud;

/** DashScope Rerank 响应在本地验证后的候选索引与独立评分。 */
public record RerankItem(int index, double score) { }
