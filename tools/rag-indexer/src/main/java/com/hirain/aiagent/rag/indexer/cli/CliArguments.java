package com.hirain.aiagent.rag.indexer.cli;

import java.nio.file.Path;

/**
 * 命令入口归一化后的路径快照。这里只做绝对化和规范化；Corpus Root 边界、符号链接和重叠关系
 * 由 G103 的专用安全校验器负责，不能在 CLI 层假装已经安全。
 */
public record CliArguments(
        String command,
        Path corpus,
        Path config,
        Path output,
        Path workDirectory,
        Path bundle,
        Path dataset,
        Path report
) {
    public static Path normalizePath(String value) {
        if (value == null || value.isBlank()) {
            throw new CliCommandException(CliExitCode.ARGUMENT_OR_CONFIG_ERROR, "CLI_ARGUMENT_INVALID");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
