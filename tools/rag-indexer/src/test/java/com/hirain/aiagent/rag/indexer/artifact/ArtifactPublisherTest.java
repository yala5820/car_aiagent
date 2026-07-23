package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArtifactPublisherTest {
    @Test
    void shouldAtomicallyPublishExactLayoutWithoutOverwritingExistingOutput() throws Exception {
        var root = Files.createTempDirectory("rag-publish-"); var staging = root.resolve(".staging-run"); Files.createDirectories(staging);
        Files.writeString(BundleLayout.data(staging), "db"); Files.writeString(BundleLayout.manifest(staging), "{}"); Files.writeString(BundleLayout.report(staging), "{}");
        var output = root.resolve("bundle"); new ArtifactPublisher().publish(staging, output, true);
        assertTrue(Files.isDirectory(output)); assertFalse(Files.exists(staging));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactPublisher().publish(output, output, true));
    }
    @Test
    void shouldKeepStagingWhenNotPublishable() throws Exception {
        var root = Files.createTempDirectory("rag-publish-fail-"); var staging = root.resolve(".staging-run"); Files.createDirectories(staging);
        assertThrows(IllegalArgumentException.class, () -> new ArtifactPublisher().publish(staging, root.resolve("bundle"), false));
        assertTrue(Files.exists(staging));
    }
}
