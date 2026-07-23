package com.hirain.aiagent.rag.indexer.cli;

/** 仅携带稳定原因码和退出类别的受控异常，禁止把底层异常文本直接暴露给控制台。 */
public final class CliCommandException extends RuntimeException {
    private final CliExitCode exitCode;
    private final String reasonCode;

    public CliCommandException(CliExitCode exitCode, String reasonCode) {
        this.exitCode = exitCode;
        this.reasonCode = reasonCode;
    }

    public CliExitCode exitCode() {
        return exitCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
