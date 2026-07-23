package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.artifact.BundleFileHasher;
import com.hirain.aiagent.rag.indexer.artifact.BundleLayout;
import com.hirain.aiagent.rag.indexer.embedding.DashScopeDocumentEmbeddingClient;
import com.hirain.aiagent.rag.indexer.embedding.EmbeddingException;
import com.hirain.aiagent.rag.indexer.evaluation.OfflineHybridEvaluator;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationDataset;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationLoader;
import com.hirain.aiagent.rag.indexer.evaluation.RetrievalEvaluationReportWriter;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import java.io.PrintStream;
import java.util.Set;

/** 只读 Hybrid 评测命令入口；不写 Store、不会把 TEST_ONLY 候选升级为正式 Bundle。 */
public final class EvaluateCommand extends AbstractPipelineCommand {
    public EvaluateCommand(BuildCancellationToken cancellationToken) { super(cancellationToken); }
    @Override protected String commandName() { return "evaluate"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--bundle", "--dataset", "--report"); }
    @Override protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        try {
            RetrievalEvaluationDataset dataset = new RetrievalEvaluationLoader().load(context.arguments().dataset());
            var metrics = new OfflineHybridEvaluator(new DashScopeDocumentEmbeddingClient()).evaluate(context.arguments().bundle(), dataset);
            String bundleHash = new BundleFileHasher().hash(BundleLayout.data(context.arguments().bundle())).sha256();
            new RetrievalEvaluationReportWriter().write(context.arguments().report(), bundleHash, dataset, metrics, "HYBRID_FUSION_ONLY_V1");
            standardOut.println("EVALUATION_SUCCESS mode=HYBRID_FUSION_ONLY_V1 cases=" + metrics.caseCount());
            return CliExitCode.SUCCESS.value();
        } catch (EmbeddingException exception) { throw new CliCommandException(CliExitCode.EMBEDDING_ERROR, exception.getMessage()); }
        catch (CliCommandException exception) { throw exception; }
        catch (IllegalArgumentException exception) { throw new CliCommandException(CliExitCode.INPUT_OR_PARSE_ERROR, controlledReason(exception)); }
        catch (Exception exception) { throw new CliCommandException(CliExitCode.INDEX_BUILD_ERROR, "EVALUATION_FAILED"); }
    }
    private static String controlledReason(IllegalArgumentException exception) {
        String value = exception.getMessage();
        return value != null && value.startsWith("EVALUATION_") ? value : "EVALUATION_FAILED";
    }
}
