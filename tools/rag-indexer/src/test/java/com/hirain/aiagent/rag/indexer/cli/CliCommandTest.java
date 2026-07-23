package com.hirain.aiagent.rag.indexer.cli;

import com.hirain.aiagent.rag.indexer.RagIndexerMain;
import com.hirain.aiagent.rag.indexer.artifact.ArtifactOutputAlreadyExistsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 G101 命令面：参数错误可预测、合法参数不泄漏本机绝对路径。 */
final class CliCommandTest {

    @Test
    void shouldRejectMissingRequiredOptionsWithoutStackTrace() {
        Invocation result = invoke("build", "--corpus", "a.json");
        assertEquals(CliExitCode.ARGUMENT_OR_CONFIG_ERROR.value(), result.exitCode());
        assertEquals("CLI_ARGUMENT_REQUIRED" + System.lineSeparator(), result.error());
    }

    @Test
    void shouldRejectMissingEvaluationDatasetWithoutPrintingLocalPaths() {
        String localPath = java.nio.file.Path.of(".").toAbsolutePath().normalize().toString();
        Invocation result = invoke(
                "evaluate", "--bundle", "bundle", "--dataset", "dataset.json", "--report", "report.json"
        );
        assertEquals(CliExitCode.INPUT_OR_PARSE_ERROR.value(), result.exitCode());
        assertTrue(result.error().contains("EVALUATION_DATASET_INVALID"));
        assertFalse(result.output().contains(localPath));
        assertFalse(result.error().contains(localPath));
    }

    @Test
    void shouldMapExistingBundleOutputToStableReasonCode() {
        CliCommandException exception = BuildCommand.failure(CliExitCode.ARTIFACT_VERIFICATION_ERROR,
                new ArtifactOutputAlreadyExistsException());

        assertEquals(CliExitCode.ARTIFACT_VERIFICATION_ERROR, exception.exitCode());
        assertEquals("BUILD_OUTPUT_ALREADY_EXISTS", exception.reasonCode());
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = RagIndexerMain.run(arguments,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Invocation(exitCode, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Invocation(int exitCode, String output, String error) {
    }
}
