package com.hirain.aiagent.rag.indexer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Phase 0 的无第三方依赖 Smoke Test。许可证 Gate 尚未锁定测试框架版本，
 * 因而通过普通 Java 入口验证 CLI 对调用方最关键的稳定行为。
 */
public final class RagIndexerMainTest {

    private RagIndexerMainTest() {
    }

    public static void main(String[] args) {
        verifyHelp();
        verifyVersion();
        verifyUnknownCommand();
        verifyAcceptedCommandIsNotPublishedPrematurely();
        RagDependencySmokeTest.verifyCandidateClasses();
    }

    private static void verifyHelp() {
        InvocationResult result = invoke("--help");
        require(result.exitCode == 0, "--help 必须成功退出");
        require(result.standardOut.contains("车辆知识 RAG 离线索引器"), "--help 必须输出产品名称");
    }

    private static void verifyVersion() {
        InvocationResult result = invoke("--version");
        require(result.exitCode == 0, "--version 必须成功退出");
        require(result.standardOut.contains(RagIndexerMain.VERSION), "--version 必须输出稳定版本");
    }

    private static void verifyUnknownCommand() {
        InvocationResult result = invoke("unknown");
        require(result.exitCode == 2, "未知命令必须返回参数错误退出码 2");
        require(result.standardError.contains("CLI_COMMAND_UNKNOWN"), "未知命令不得泄漏堆栈");
    }

    private static void verifyAcceptedCommandIsNotPublishedPrematurely() {
        InvocationResult result = invoke(
                "validate", "--corpus", "corpus.json", "--config", "rag-build.json", "--work-dir", "work"
        );
        require(result.exitCode == 2, "缺失 Corpus/Config 的 validate 必须返回受控配置错误");
        require(result.standardError.contains("CORPUS_JSON_INVALID"), "validate 不得把输入读取失败伪装成成功或泄漏堆栈");
    }

    private static InvocationResult invoke(String... arguments) {
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        int exitCode = RagIndexerMain.run(
                arguments,
                new PrintStream(standardOut, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8)
        );
        return new InvocationResult(
                exitCode,
                standardOut.toString(StandardCharsets.UTF_8),
                standardError.toString(StandardCharsets.UTF_8)
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record InvocationResult(int exitCode, String standardOut, String standardError) {
    }
}
