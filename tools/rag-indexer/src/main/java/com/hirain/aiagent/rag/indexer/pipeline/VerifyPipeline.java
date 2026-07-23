package com.hirain.aiagent.rag.indexer.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hirain.aiagent.rag.indexer.artifact.BundleFileHasher;
import com.hirain.aiagent.rag.indexer.artifact.BundleLayout;
import com.hirain.aiagent.rag.indexer.artifact.ManifestSchemaContractValidator;
import com.hirain.aiagent.rag.indexer.artifact.ManifestStoreConsistencyValidator;
import com.hirain.aiagent.rag.indexer.store.ObjectBoxStoreVerifier;

import java.io.IOException;
import java.nio.file.Path;

/** 只读 Verify：不请求 Embedding API、不修改 Bundle，拒绝 Hash/Schema/Store 任一不一致。 */
public final class VerifyPipeline {
    public void verifyUsingBundledSchema(Path bundleDirectory) throws IOException {
        try (var input = VerifyPipeline.class.getResourceAsStream("/knowledge-bundle-manifest.schema.json")) {
            if (input == null) throw new IllegalStateException("缺少打包的共享 Manifest Schema");
            verify(bundleDirectory, input);
        }
    }
    public void verify(Path bundleDirectory, Path sharedManifestSchema) throws IOException {
        try (var input = java.nio.file.Files.newInputStream(sharedManifestSchema)) { verify(bundleDirectory, input); }
    }
    private void verify(Path bundleDirectory, java.io.InputStream sharedManifestSchema) throws IOException {
        try {
            Path manifest = BundleLayout.manifest(bundleDirectory); Path data = BundleLayout.data(bundleDirectory);
            new ManifestSchemaContractValidator().validate(sharedManifestSchema, manifest);
            JsonNode root = new ObjectMapper().readTree(manifest.toFile()); var actual = new BundleFileHasher().hash(data);
            if (actual.sizeBytes() != root.path("dataFile").path("sizeBytes").asLong() || !actual.sha256().equals(root.path("dataFile").path("sha256").asText())) throw new IllegalArgumentException("data.mdb Hash 不一致");
            new ObjectBoxStoreVerifier().verify(bundleDirectory);
            new ManifestStoreConsistencyValidator().validate(manifest, bundleDirectory);
        } finally {
            // Verify 需要短暂打开 Store；无论通过或失败，都不把 ObjectBox 运行锁遗留为 Bundle 内容。
            java.nio.file.Files.deleteIfExists(BundleLayout.runtimeLock(bundleDirectory));
        }
    }
}
