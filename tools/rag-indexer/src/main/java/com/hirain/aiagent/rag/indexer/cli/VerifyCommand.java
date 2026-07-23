package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import com.hirain.aiagent.rag.indexer.pipeline.VerifyPipeline;
import com.hirain.aiagent.rag.indexer.config.ConfigLoader;
import java.io.PrintStream;
import java.util.Set;

/** 已有 Bundle 验证命令入口；独立 Store/Manifest 校验属于后续 G402。 */
public final class VerifyCommand extends AbstractPipelineCommand {
    public VerifyCommand(BuildCancellationToken cancellationToken) { super(cancellationToken); }
    @Override protected String commandName() { return "verify"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--bundle", "--config"); }
    @Override protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        new ConfigLoader().load(context.arguments().config());
        try {
            context.cancellationToken().throwIfCancelled();
            new VerifyPipeline().verifyUsingBundledSchema(context.arguments().bundle());
            standardOut.println("VERIFY_SUCCESS");
            return CliExitCode.SUCCESS.value();
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new CliCommandException(CliExitCode.NOT_PUBLISHABLE, "VERIFY_FAILED");
        }
    }
}
