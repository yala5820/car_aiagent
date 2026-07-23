package com.hirain.aiagent.rag.indexer.report;

import java.util.List;
import java.util.Map;

/** 集中计算发布状态，避免调用方手工写入 publishable。 */
public final class BuildReportCollector {
    public BuildReport collect(String runId, List<String> errors, List<String> warnings, ParserBuildSummary parser,
                               ChunkBuildSummary chunk, EmbeddingBuildSummary embedding, StoreBuildSummary store,
                               boolean configurationApproved, boolean manifestValidated) {
        boolean publishable = new PublishabilityEvaluator().evaluate(errors, embedding.failedCount() == 0
                && embedding.requestedCount() == embedding.succeededCount() + embedding.cacheHitCount(), configurationApproved,
                store.documentCount() >= 0, manifestValidated);
        return new BuildReport(runId, publishable, List.copyOf(errors), List.copyOf(warnings), parser.documentCounts(),
                Map.of("documents", store.documentCount(), "parents", store.parentChunkCount(), "children", store.childChunkCount(), "terms", store.lexicalTermCount()),
                parser, chunk, embedding, store, List.of());
    }
}
