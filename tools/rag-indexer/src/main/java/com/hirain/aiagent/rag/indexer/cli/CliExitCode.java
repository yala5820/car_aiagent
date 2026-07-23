package com.hirain.aiagent.rag.indexer.cli;

/**
 * 对脚本和 CI 稳定公开的 CLI 退出码。新增原因码只能映射到既有类别，避免调用方依赖 Java 异常文本。
 */
public enum CliExitCode {
    SUCCESS(0),
    ARGUMENT_OR_CONFIG_ERROR(2),
    INPUT_OR_PARSE_ERROR(3),
    EMBEDDING_ERROR(4),
    INDEX_BUILD_ERROR(5),
    ARTIFACT_VERIFICATION_ERROR(6),
    NOT_PUBLISHABLE(7),
    CANCELLED(130);

    private final int value;

    CliExitCode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
