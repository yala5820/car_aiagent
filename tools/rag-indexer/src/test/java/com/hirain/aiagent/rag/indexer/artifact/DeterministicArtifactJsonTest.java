package com.hirain.aiagent.rag.indexer.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class DeterministicArtifactJsonTest {
    @Test
    void shouldWriteSameManifestBytesForSameInput() throws Exception {
        var first = Files.createTempFile("manifest-first-", ".json"); var second = Files.createTempFile("manifest-second-", ".json");
        var manifest = new KnowledgeBundleManifest(1,"b","1","s","v",0,"5.4.0","sha256:"+"a".repeat(64),1,new BundleFileHasher.FileHash(1,"a".repeat(64)),
                java.util.Map.of("provider","DashScope","model","text-embedding-v4","dimension",1024,"distanceType","COSINE","templateVersion",1),java.util.Map.of("configFingerprint","sha256:"+"a".repeat(64)),java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of());
        new ManifestWriter().write(first, manifest); new ManifestWriter().write(second, manifest);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }
}
