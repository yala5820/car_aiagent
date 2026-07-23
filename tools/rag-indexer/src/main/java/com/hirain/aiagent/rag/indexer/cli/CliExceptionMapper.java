package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.pipeline.BuildCancellationToken;

import java.io.PrintStream;

/** 将所有入口异常收敛为不含敏感内容的稳定诊断。 */
public final class CliExceptionMapper {
    private CliExceptionMapper() {
    }

    public static int writeControlledError(Throwable error, PrintStream standardError) {
        if (error instanceof CliCommandException commandException) {
            standardError.println(commandException.reasonCode());
            return commandException.exitCode().value();
        }
        if (error instanceof BuildCancellationToken.BuildCancelledException) {
            standardError.println("BUILD_CANCELLED");
            return CliExitCode.CANCELLED.value();
        }
        standardError.println("INTERNAL_FAILURE");
        return CliExitCode.INDEX_BUILD_ERROR.value();
    }
}
