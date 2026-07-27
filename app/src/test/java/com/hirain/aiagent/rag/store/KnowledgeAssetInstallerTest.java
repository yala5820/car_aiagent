package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.model.VehicleProfile;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 安装器仅在 staging 完整验证后提交，版本冲突和缺失输入都不得影响现有目录。 */
public class KnowledgeAssetInstallerTest {
    private static final Path CANDIDATE = Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1");
    private static final VehicleProfile PROFILE = new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0);

    @Test public void installsThenReusesVerifiedDevelopmentCandidate() throws Exception {
        Path files = Files.createTempDirectory("rag-install-");
        KnowledgeAssetInstaller installer = installer(files);
        KnowledgeInstallResult first = installer.install(plan(source(manifest()), "first"));
        assertTrue(first.installed());
        assertTrue(Files.isRegularFile(files.resolve("rag/stores/0.0.1/data.mdb")));
        assertTrue(Files.isRegularFile(files.resolve("rag/active.json")));
        KnowledgeInstallResult second = installer.install(plan(source(manifest()), "second"));
        assertTrue(second.reused());
    }

    @Test public void rejectsReusedVersionWithDifferentContentWithoutOverwriting() throws Exception {
        Path files = Files.createTempDirectory("rag-install-");
        KnowledgeAssetInstaller installer = installer(files);
        assertTrue(installer.install(plan(source(manifest()), "first")).installed());
        String changed = manifest().replace("84fde8f27c282150769786757a935948e28d6a33e57651a21a5b4b072d6d914b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        KnowledgeInstallResult result = installer.install(plan(source(changed), "second"));
        assertFalse(result.installed());
        assertEquals("BUNDLE_VERSION_REUSED_WITH_DIFFERENT_CONTENT", result.reasonCode());
    }

    @Test public void recoversOnlyCommittedActivePointer() throws Exception {
        Path files = Files.createTempDirectory("rag-install-");
        KnowledgeAssetInstaller installer = installer(files);
        assertTrue(installer.install(plan(source(manifest()), "first")).installed());
        Files.write(files.resolve("rag/active.json.tmp"), "not-a-pointer".getBytes(StandardCharsets.UTF_8));
        KnowledgeInstallResult recovered = installer.recoverActive();
        assertTrue(recovered.reused());
        assertEquals("0.0.1", recovered.manifest().bundleVersion());
    }

    @Test public void reportsMissingAssetAndRejectsUnsafeInstallId() throws Exception {
        Path files = Files.createTempDirectory("rag-install-");
        KnowledgeAssetInstaller installer = installer(files);
        KnowledgeAssetSource absent = path -> { throw new FileNotFoundException(path); };
        assertEquals("KNOWLEDGE_ASSET_MISSING", installer.install(plan(absent, "missing")).reasonCode());
        try { plan(absent, "../escape"); throw new AssertionError("不允许 staging 路径逃逸"); }
        catch (IllegalArgumentException expected) { assertEquals("BUNDLE_PATH_INVALID", expected.getMessage()); }
    }

    @Test public void rejectsInstallBeforeCopyWhenCapacityIsInsufficient() throws Exception {
        Path files = Files.createTempDirectory("rag-install-");
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(new KnowledgeStorageLayout(files), (directory, bytes) -> false, (directory, value) -> KnowledgeStoreValidationResult.success());
        assertEquals("KNOWLEDGE_STORAGE_INSUFFICIENT", installer.install(plan(source(manifest()), "small")).reasonCode());
    }

    private static KnowledgeAssetInstaller installer(Path files) {
        return new KnowledgeAssetInstaller(new KnowledgeStorageLayout(files), (directory, bytes) -> true, (directory, manifest) -> KnowledgeStoreValidationResult.success());
    }
    private static KnowledgeInstallPlan plan(KnowledgeAssetSource source, String installId) { return new KnowledgeInstallPlan(source, "bundle", PROFILE, installId); }
    private static String manifest() throws Exception { return new String(Files.readAllBytes(CANDIDATE.resolve("manifest.json")), StandardCharsets.UTF_8); }
    private static KnowledgeAssetSource source(String manifest) throws Exception {
        Map<String, byte[]> files = new HashMap<>(); files.put("bundle/manifest.json", manifest.getBytes(StandardCharsets.UTF_8)); files.put("bundle/data.mdb", Files.readAllBytes(CANDIDATE.resolve("data.mdb")));
        return path -> { byte[] bytes = files.get(path); if (bytes == null) throw new FileNotFoundException(path); return new ByteArrayInputStream(bytes); };
    }
}
