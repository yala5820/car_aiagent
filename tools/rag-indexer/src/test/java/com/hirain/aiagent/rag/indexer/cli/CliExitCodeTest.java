package com.hirain.aiagent.rag.indexer.cli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 退出码是外部脚本接口，必须唯一且与总体设计表一致。 */
final class CliExitCodeTest {

    @Test
    void shouldKeepStableAndUniqueExitCodes() {
        assertEquals(0, CliExitCode.SUCCESS.value());
        assertEquals(2, CliExitCode.ARGUMENT_OR_CONFIG_ERROR.value());
        assertEquals(3, CliExitCode.INPUT_OR_PARSE_ERROR.value());
        assertEquals(4, CliExitCode.EMBEDDING_ERROR.value());
        assertEquals(5, CliExitCode.INDEX_BUILD_ERROR.value());
        assertEquals(6, CliExitCode.ARTIFACT_VERIFICATION_ERROR.value());
        assertEquals(7, CliExitCode.NOT_PUBLISHABLE.value());
        assertEquals(130, CliExitCode.CANCELLED.value());
        assertEquals(CliExitCode.values().length,
                Arrays.stream(CliExitCode.values()).map(CliExitCode::value).distinct().count());
        assertTrue(CliExitCode.CANCELLED.value() > CliExitCode.NOT_PUBLISHABLE.value());
    }
}
