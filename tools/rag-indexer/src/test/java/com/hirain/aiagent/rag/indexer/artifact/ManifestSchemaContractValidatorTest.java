package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class ManifestSchemaContractValidatorTest {
    @Test
    void shouldValidateWrittenManifestAgainstSharedRequiredFields() throws Exception {
        Path output = Files.createTempFile("manifest-", ".json");
        new ManifestWriter().write(output, ManifestValidatorTest.manifest("DashScope"));
        assertDoesNotThrow(() -> new ManifestSchemaContractValidator().validate(
                Path.of("..", "..", "rag-schema", "contracts", "knowledge-bundle-manifest.schema.json"), output));
    }
}
