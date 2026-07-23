package com.hirain.aiagent.rag.indexer.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashScopeApiKeyProviderTest {
    @TempDir Path root;
    @Test void readsRootLocalPropertiesWithoutExposingItsPath() throws Exception {
        Files.writeString(root.resolve("local.properties"), "dashscope.api_key=test-only-key\n");
        assertEquals("test-only-key", DashScopeApiKeyProvider.load(root.resolve("a/b")));
    }
    @Test void missingConfigurationReturnsNull() {
        assertNull(DashScopeApiKeyProvider.load(root));
    }
}
