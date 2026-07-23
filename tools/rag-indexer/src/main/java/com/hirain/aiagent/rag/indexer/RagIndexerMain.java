package com.hirain.aiagent.rag.indexer;

import com.hirain.aiagent.rag.indexer.cli.CliExceptionMapper;
import com.hirain.aiagent.rag.indexer.cli.RagIndexerCommand;
import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;

import java.io.PrintStream;
import java.util.Objects;

/**
 * 离线 RAG 索引器的受控入口。
 *
 * <p>入口只负责进程边界、受控退出码和取消信号；具体命令不直接依赖 Parser 或 ObjectBox。
 * 这样后续 Pipeline 可以逐阶段接入，同时保持 Shell 调用方可审计的错误契约。</p>
 */
public final class RagIndexerMain {

    /** 仅保留兼容旧测试的公开别名；真实版本从构建产物 Manifest 读取。 */
    public static final String VERSION = RagIndexerCommand.version();

    private RagIndexerMain() {
    }

    public static void main(String[] args) {
        BuildCancellationToken cancellationToken = new BuildCancellationToken();
        Runtime.getRuntime().addShutdownHook(new Thread(
                cancellationToken::cancel,
                "rag-indexer-cancellation-hook"
        ));
        int exitCode = run(args, System.out, System.err, cancellationToken);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 将参数处理与进程退出分离，使测试能验证稳定退出码，且后续命令层可复用同一错误契约。
     *
     * @return 稳定的 {@code CliExitCode} 数值；该重载用于不涉及进程 Shutdown Hook 的单元测试。
     */
    public static int run(String[] args, PrintStream standardOut, PrintStream standardError) {
        return run(args, standardOut, standardError, new BuildCancellationToken());
    }

    /**
     * 将参数处理、取消令牌与进程退出分离。默认输出绝不包含异常堆栈、凭证、完整正文或绝对路径。
     */
    public static int run(
            String[] args,
            PrintStream standardOut,
            PrintStream standardError,
            BuildCancellationToken cancellationToken
    ) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(standardOut, "standardOut");
        Objects.requireNonNull(standardError, "standardError");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        try {
            return new RagIndexerCommand(cancellationToken).execute(args, standardOut);
        } catch (Throwable error) {
            return CliExceptionMapper.writeControlledError(error, standardError);
        }
    }
}
