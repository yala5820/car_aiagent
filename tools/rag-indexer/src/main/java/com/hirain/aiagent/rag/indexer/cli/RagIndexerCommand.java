package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Objects;

/** CLI 根路由，只负责命令选择和帮助；不得直接访问输入文档、网络或 ObjectBox。 */
public final class RagIndexerCommand {
    private final BuildCancellationToken cancellationToken;

    public RagIndexerCommand(BuildCancellationToken cancellationToken) {
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }

    public int execute(String[] arguments, PrintStream standardOut) {
        if (arguments.length == 0 || isHelp(arguments[0])) {
            printHelp(standardOut);
            return CliExitCode.SUCCESS.value();
        }
        if ("--version".equals(arguments[0]) || "-V".equals(arguments[0])) {
            standardOut.println("rag-indexer " + version());
            return CliExitCode.SUCCESS.value();
        }

        String command = arguments[0];
        String[] commandArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        if (commandArguments.length == 1 && isHelp(commandArguments[0])) {
            printCommandHelp(command, standardOut);
            return CliExitCode.SUCCESS.value();
        }
        return switch (command) {
            case "validate" -> new ValidateCommand(cancellationToken).execute(commandArguments, standardOut);
            case "build" -> new BuildCommand(cancellationToken).execute(commandArguments, standardOut);
            case "verify" -> new VerifyCommand(cancellationToken).execute(commandArguments, standardOut);
              case "evaluate" -> new EvaluateCommand(cancellationToken).execute(commandArguments, standardOut);
              case "evaluate-rerank" -> new EvaluateRerankCommand(cancellationToken).execute(commandArguments, standardOut);
              case "evaluate-v2" -> new EvaluateV2Command(cancellationToken, false).execute(commandArguments, standardOut);
              case "evaluate-v2-rerank" -> new EvaluateV2Command(cancellationToken, true).execute(commandArguments, standardOut);
              case "evaluate-v2-ablation" -> new EvaluateAblationCommand(cancellationToken).execute(commandArguments, standardOut);
            default -> throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_COMMAND_UNKNOWN");
        };
    }

    public static String version() {
        String version = RagIndexerCommand.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static void printHelp(PrintStream output) {
        output.println("车辆知识 RAG 离线索引器");
        output.println("用法：rag-indexer <validate|build|verify|evaluate|evaluate-v2|evaluate-v2-rerank|evaluate-v2-ablation> [选项]");
        output.println("  validate --corpus <corpus.json> --config <rag-build.json> --work-dir <dir>");
        output.println("  build    --corpus <corpus.json> --config <rag-build.json> --output <dir> --work-dir <dir>");
        output.println("  verify   --bundle <output-dir> --config <rag-build.json>");
        output.println("  evaluate --bundle <output-dir> --dataset <evaluation.json> --report <file>");
        output.println("  evaluate-v2 --bundle <output-dir> --dataset <evaluation-v2.json> --report <file>");
        output.println("  evaluate-v2-rerank --bundle <output-dir> --dataset <evaluation-v2.json> --report <file>");
        output.println("  evaluate-v2-ablation --bundle <output-dir> --dataset <evaluation-v2.json> --report <file>");
        output.println("使用 rag-indexer <command> --help 查看命令边界；API Key 可来自环境变量或仓库根目录 local.properties，绝不写入语料配置和构建报告。");
    }

    private static void printCommandHelp(String command, PrintStream output) {
        switch (command) {
            case "validate", "build", "verify", "evaluate", "evaluate-v2", "evaluate-v2-rerank", "evaluate-v2-ablation" -> {
                printHelp(output);
                output.println("validate 只执行输入与 Parser 质量校验，并在 work/runs/<runId>/ 生成不含正文的 parser-review.json；"
                        + "build 生成 Bundle；verify 独立验证 Bundle；evaluate 只读计算与 Android 对齐的 Dense+BM25+RRF Hybrid 指标。");
            }
            default -> throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_COMMAND_UNKNOWN");
        }
    }
}
