package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

/** Dense 阶段只保存 ObjectBox 距离；距离越小越接近，后续 RRF 不误用为可比较分数。 */
public record DenseCandidate(KnowledgeChunkEntity chunk, double distance) { }
