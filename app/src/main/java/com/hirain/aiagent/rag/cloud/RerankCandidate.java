package com.hirain.aiagent.rag.cloud;

/** Rerank 仅携带候选标题路径与受预算限制的短文本，不携带 Metadata。 */
public record RerankCandidate(String headingPath, String text) { }
