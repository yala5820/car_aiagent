package com.hirain.aiagent.rag.indexer.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Golden 比较统一为 UTF-8 精确比较，更新测试期望必须同步审查 changeReason。 */
public final class GoldenFileAssertions {
    private GoldenFileAssertions() { }
    public static void assertUtf8Equals(Path expected, String actual) throws Exception {
        assertEquals(Files.readString(expected, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                actual.replace("\r\n", "\n"));
    }
}
