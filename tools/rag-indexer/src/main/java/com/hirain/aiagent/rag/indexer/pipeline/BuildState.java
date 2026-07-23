package com.hirain.aiagent.rag.indexer.pipeline;

import java.util.List;

/** 整次 Build 的显式中间状态；后续 Store Model 只能从该状态消费各阶段结果。 */
public record BuildState(List<DocumentBuildState> documents) {
    public BuildState { documents = List.copyOf(documents); }
}
