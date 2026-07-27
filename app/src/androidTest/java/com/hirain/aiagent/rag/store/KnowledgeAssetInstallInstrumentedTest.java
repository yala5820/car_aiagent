package com.hirain.aiagent.rag.store;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import java.io.File;
import java.nio.file.Path;
import org.junit.Test;

/** 在 Android 运行时验证真实 AssetManager、filesDir 复制、Hash、ObjectBox 试开和活动指针提交。 */
public class KnowledgeAssetInstallInstrumentedTest {
    @Test public void installsMainModelYBundleFromTargetAssets() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File files = new File(target.getCacheDir(), "rag-main-asset-" + System.nanoTime());
        assertTrue(files.mkdirs());
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(
                new KnowledgeStorageLayout(files.toPath()),
                (directory, bytes) -> true,
                (directory, manifest) -> verify(target, directory, manifest));
        VehicleProfile profile = new VehicleProfile("MODEL_Y", "2026", "CN", "2026_REFRESH", "RWD", 0);
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(
                new AndroidKnowledgeAssetSource(target.getAssets()),
                "rag/knowledge_db",
                profile,
                "main-asset-instrumented"));
        assertTrue("main APK Bundle install failed: " + result.reasonCode(), result.installed());
        assertTrue(installer.recoverActive().reused());
    }

    @Test public void installsG404DevelopmentCandidateFromTestAssets() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        File files = new File(target.getCacheDir(), "rag-install-" + System.nanoTime());
        assertTrue(files.mkdirs());
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(new KnowledgeStorageLayout(files.toPath()), (directory, bytes) -> true, (directory, manifest) -> verify(target, directory, manifest));
        VehicleProfile profile = new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0);
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(new AndroidKnowledgeAssetSource(test.getAssets()), "rag/development_candidate", profile, "instrumented"));
        assertTrue("development candidate install failed: " + result.reasonCode(), result.installed());
        assertTrue(installer.recoverActive().reused());
    }
    @Test public void coordinatorInitializesMainModelYAsset() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KnowledgeStoreCoordinator coordinator = new KnowledgeStoreCoordinator(target, new KnowledgeStoreManager());
        try {
            coordinator.initializeAsync(new VehicleProfile("MODEL_Y", "2026", "CN", "2026_REFRESH", "RWD", 0));
            long deadline = System.currentTimeMillis() + 5_000L;
            while (coordinator.snapshot().state() != KnowledgeStoreState.READY
                    && coordinator.snapshot().state() != KnowledgeStoreState.FAILED
                    && System.currentTimeMillis() < deadline) Thread.sleep(20L);
            assertEquals(KnowledgeStoreState.READY, coordinator.snapshot().state());
            assertEquals("TEST_ONLY-model-y-2026-refresh-v4-expanded", coordinator.snapshot().bundleVersion());
        } finally { coordinator.close(); }
    }
    private static KnowledgeStoreValidationResult verify(Context context, Path directory, KnowledgeBundleManifest manifest) {
        try (io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(context).directory(directory.toFile()).build()) {
            java.util.List<KnowledgeStoreMetadataEntity> values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
            return values.size() == 1 ? new KnowledgeStoreCompatibilityValidator().validate(manifest, values.get(0)) : KnowledgeStoreValidationResult.failure("STORE_METADATA_COUNT_INVALID");
        } catch (Exception error) { return KnowledgeStoreValidationResult.failure("STORE_OPEN_FAILED"); }
    }
}
