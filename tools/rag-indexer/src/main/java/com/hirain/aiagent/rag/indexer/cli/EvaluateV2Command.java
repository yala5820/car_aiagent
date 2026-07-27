package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.artifact.BundleFileHasher;
import com.hirain.aiagent.rag.indexer.artifact.BundleLayout;
import com.hirain.aiagent.rag.indexer.embedding.DashScopeDocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.evaluation.OfflineParentEvidenceEvaluator;
import com.hirain.aiagent.rag.indexer.evaluation.ParentEvidenceReportWriter;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationDatasetV2;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationLoaderV2;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import java.io.PrintStream;
import java.util.Set;

/** Eval V2 只读入口；区分 RRF 与真实 Rerank 模式，永不修改 Bundle。 */
public final class EvaluateV2Command extends AbstractPipelineCommand {
    private final boolean rerankEnabled;

    public EvaluateV2Command(BuildCancellationToken token, boolean rerankEnabled) {
        super(token);
        this.rerankEnabled = rerankEnabled;
    }

    @Override protected String commandName() { return rerankEnabled ? "evaluate-v2-rerank" : "evaluate-v2"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--bundle", "--dataset", "--report"); }

    @Override protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        try {
            RetrievalEvaluationDatasetV2 dataset = new RetrievalEvaluationLoaderV2().load(context.arguments().dataset());
            var metrics = new OfflineParentEvidenceEvaluator(new DashScopeDocumentEmbeddingClient(), rerankEnabled)
                    .evaluate(context.arguments().bundle(), dataset);
            String hash = new BundleFileHasher().hash(BundleLayout.data(context.arguments().bundle())).sha256();
            new ParentEvidenceReportWriter().write(context.arguments().report(), hash, dataset, metrics,
                    rerankEnabled ? "PARENT_EVIDENCE_RERANKED_V2" : "PARENT_EVIDENCE_RRF_V2");
            standardOut.println("EVALUATION_V2_SUCCESS mode=" + (rerankEnabled ? "PARENT_EVIDENCE_RERANKED_V2" : "PARENT_EVIDENCE_RRF_V2")
                    + " cases=" + metrics.caseCount());
            return CliExitCode.SUCCESS.value();
        } catch (EmbeddingException exception) {
            throw new CliCommandException(CliExitCode.EMBEDDING_ERROR, exception.getMessage());
        } catch (CliCommandException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            String reason = exception.getMessage();
            throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR,
                    reason != null && reason.startsWith("EVALUATION_V2_") ? reason : "EVALUATION_V2_FAILED");
        } catch (Exception exception) {
            throw new CliCommandException(CliExitCode.INDEX_BUILD_ERROR, "EVALUATION_V2_FAILED");
        }
    }
}
