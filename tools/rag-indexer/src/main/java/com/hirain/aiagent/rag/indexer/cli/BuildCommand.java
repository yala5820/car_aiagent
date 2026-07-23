package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;
import com.hirain.aiagent.rag.indexer.pipeline.DevelopmentBuildPipeline;
import java.io.PrintStream;
import java.util.Set;

/** 完整构建命令的入口；G101 禁止在此层直接解析文件或写 Store。 */
public final class BuildCommand extends AbstractPipelineCommand {
    public BuildCommand(BuildCancellationToken cancellationToken) { super(cancellationToken); }
    @Override protected String commandName() { return "build"; }
    @Override protected Set<String> requiredOptions() { return Set.of("--corpus", "--config", "--output", "--work-dir"); }
    @Override protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        try {
            var result = new DevelopmentBuildPipeline().build(context);
            standardOut.println(result.publishable() ? "BUILD_PUBLISHED" : "BUILD_DEVELOPMENT_BUNDLE_READY");
            return CliExitCode.SUCCESS.value();
        } catch (CliCommandException exception) {
            // 已受控的原因码不含正文、路径或凭证，必须原样交给统一异常映射器，避免被泛化为 BUILD_FAILED。
            throw exception;
        } catch (com.hirain.aiagent.rag.indexer.embedding.EmbeddingException exception) {
            throw new CliCommandException(CliExitCode.EMBEDDING_ERROR, exception.getMessage());
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw failure(CliExitCode.ARTIFACT_VERIFICATION_ERROR, exception);
        } catch (Exception exception) {
            throw failure(CliExitCode.INDEX_BUILD_ERROR, exception);
        }
    }

    /**
     * 控制台仅保留异常类型这一非敏感诊断维度，绝不输出底层 message，避免 PDF 正文、绝对路径或凭证随异常泄露。
     */
    static CliCommandException failure(CliExitCode exitCode, Exception exception) {
        if (exception instanceof com.hirain.aiagent.rag.indexer.artifact.ArtifactOutputAlreadyExistsException) {
            return new CliCommandException(exitCode, "BUILD_OUTPUT_ALREADY_EXISTS");
        }
        if ("CHUNK_NON_CONTIGUOUS_LOCATOR".equals(exception.getMessage())) {
            return new CliCommandException(exitCode, "BUILD_FAILED_CHUNK_NON_CONTIGUOUS_LOCATOR");
        }
        return new CliCommandException(exitCode, "BUILD_FAILED_" + exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT));
    }
}
