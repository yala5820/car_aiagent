package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;
import com.hirain.aiagent.rag.indexer.pipeline.BuildExecutionContext;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 四个子命令共享的严格选项解析与受控 Pipeline 占位边界。 */
abstract class AbstractPipelineCommand {
    private final BuildCancellationToken cancellationToken;

    AbstractPipelineCommand(BuildCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    final int execute(String[] rawArguments, PrintStream standardOut) {
        cancellationToken.throwIfCancelled();
        CliArguments arguments = parse(rawArguments);
        BuildExecutionContext context = BuildExecutionContext.create(arguments, cancellationToken);
        context.cancellationToken().throwIfCancelled();
        return executePipeline(context, standardOut);
    }

    protected abstract String commandName();

    protected abstract Set<String> requiredOptions();

    private CliArguments parse(String[] rawArguments) {
        if (rawArguments.length % 2 != 0) {
            throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_ARGUMENT_INVALID");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < rawArguments.length; index += 2) {
            String option = rawArguments[index];
            String value = rawArguments[index + 1];
            if (!requiredOptions().contains(option) || values.putIfAbsent(option, value) != null) {
                throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_ARGUMENT_INVALID");
            }
        }
        if (values.size() != requiredOptions().size() || !values.keySet().containsAll(requiredOptions())) {
            throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_ARGUMENT_REQUIRED");
        }
        return new CliArguments(
                commandName(),
                path(values, "--corpus"),
                path(values, "--config"),
                path(values, "--output"),
                path(values, "--work-dir"),
                path(values, "--bundle"),
                path(values, "--dataset"),
                path(values, "--report")
        );
    }

    private static java.nio.file.Path path(Map<String, String> values, String option) {
        String value = values.get(option);
        return value == null ? null : CliArguments.normalizePath(value);
    }

    /**
     * G101 不提前调用后续 Parser/Store Pipeline。返回不可发布码，确保脚本不会把空实现当作成功构建。
     */
    protected int executePipeline(BuildExecutionContext context, PrintStream standardOut) {
        standardOut.println("COMMAND_CONTEXT_READY command=" + context.arguments().command() + " runId=" + context.runId());
        throw new CliCommandException(CliExitCode.NOT_PUBLISHABLE, "PIPELINE_NOT_AVAILABLE");
    }
}
