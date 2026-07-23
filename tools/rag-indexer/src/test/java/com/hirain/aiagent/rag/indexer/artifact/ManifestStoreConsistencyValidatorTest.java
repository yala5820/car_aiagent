package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class ManifestStoreConsistencyValidatorTest {
    @Test
    void shouldRejectFixtureWhoseManifestAndStoreWereNotBuiltFromSameConfiguration() {
        Path fixture = Path.of("..", "..", "rag-schema", "test-fixtures", "objectbox-v1");
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestStoreConsistencyValidator().validate(fixture.resolve("manifest.json"), fixture));
    }
}
