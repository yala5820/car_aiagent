package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.artifact.BundleFileHasher;
import com.hirain.aiagent.rag.indexer.artifact.BundleLayout;
import com.hirain.aiagent.rag.indexer.embedding.DashScopeDocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.evaluation.OfflineHybridEvaluator;
import com.hirain.aiagent.rag.indexer.evaluation.OfflineParentEvidenceEvaluator;
import com.hirain.aiagent.rag.indexer.evaluation.ParentEvidenceAblationReportWriter;
import com.hirain.aiagent.rag.indexer.evaluation.ParentEvidenceMetrics;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationDatasetV2;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationLoaderV2;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在同一 Bundle 和数据集上执行固定消融矩阵；每个模式只改变检索链路开关。 */
public final class EvaluateAblationCommand extends AbstractPipelineCommand {
    public EvaluateAblationCommand(BuildCancellationToken token) { super(token); }

    @Override protected String commandName() { return "evaluate-v2-ablation"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--bundle", "--dataset", "--report"); }

    @Override protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        try {
            RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(context.arguments().dataset());
            String hash = new BundleFileHasher().hash(BundleLayout.data(context.arguments().bundle())).sha256();
            List<OfflineHybridEvaluator.AblationMode> modes = List.of(
                    OfflineHybridEvaluator.AblationMode.DENSE_ONLY,
                    OfflineHybridEvaluator.AblationMode.BM25_ONLY,
                    OfflineHybridEvaluator.AblationMode.RRF_RAW,
                    OfflineHybridEvaluator.AblationMode.RRF_EXACT_DEDUP,
                    OfflineHybridEvaluator.AblationMode.RRF_NEAR_DEDUP,
                    OfflineHybridEvaluator.AblationMode.RRF_RERANK,
                    OfflineHybridEvaluator.AblationMode.RRF_RERANK_FALLBACK);
            Map<String, ParentEvidenceMetrics> results = new LinkedHashMap<>();
            for (OfflineHybridEvaluator.AblationMode mode : modes) {
                ParentEvidenceMetrics metrics = new OfflineParentEvidenceEvaluator(
                        new DashScopeDocumentEmbeddingClient(), mode).evaluate(context.arguments().bundle(), dataset);
                results.put(mode.name(), metrics);
            }
            new ParentEvidenceAblationReportWriter().write(context.arguments().report(), hash, dataset, results);
            standardOut.println("EVALUATION_V2_ABLATION_SUCCESS modes=" + results.size() + " cases=" + dataset.cases().size());
            return CliExitCode.SUCCESS.value();
        } catch (Exception exception) {
            throw new CliCommandException(CliExitCode.INDEX_BUILD_ERROR, "EVALUATION_V2_ABLATION_FAILED");
        }
    }
}
