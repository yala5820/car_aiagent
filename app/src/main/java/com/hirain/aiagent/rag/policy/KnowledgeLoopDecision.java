package com.hirain.aiagent.rag.policy;
/** REQUIRED 请求在本迭代是否必须要求模型调用知识 Tool。 */
public record KnowledgeLoopDecision(boolean toolRequired, String reasonCode) { }
