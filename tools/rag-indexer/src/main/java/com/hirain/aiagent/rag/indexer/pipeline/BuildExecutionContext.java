package com.hirain.aiagent.rag.indexer.pipeline;

import com.hirain.aiagent.rag.indexer.cli.CliArguments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 单次命令的不可变运行快照；后续 Pipeline 只能读取它，不能从全局状态重算路径或取消状态。 */
public record BuildExecutionContext(
        String runId,
        Instant startedAt,
        CliArguments arguments,
        BuildCancellationToken cancellationToken
) {
    public BuildExecutionContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
    }

    public static BuildExecutionContext create(CliArguments arguments, BuildCancellationToken cancellationToken) {
        return new BuildExecutionContext(UUID.randomUUID().toString(), Instant.now(), arguments, cancellationToken);
    }
}
