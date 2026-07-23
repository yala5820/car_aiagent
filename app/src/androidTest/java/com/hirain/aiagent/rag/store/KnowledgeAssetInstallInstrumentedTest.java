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
    @Test public void installsG404DevelopmentCandidateFromTestAssets() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        File files = new File(target.getCacheDir(), "rag-install-" + System.nanoTime());
        assertTrue(files.mkdirs());
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(new KnowledgeStorageLayout(files.toPath()), (directory, bytes) -> true, (directory, manifest) -> verify(target, directory, manifest));
        VehicleProfile profile = new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0);
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(new AndroidKnowledgeAssetSource(test.getAssets()), "rag/development_candidate", profile, "instrumented"));
        assertTrue(result.installed());
        assertTrue(installer.recoverActive().reused());
    }
    @Test public void missingMainAssetFailsOnlyKnowledgeInitialization() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        KnowledgeStoreCoordinator coordinator = new KnowledgeStoreCoordinator(target, new KnowledgeStoreManager());
        try {
            coordinator.initializeAsync(new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0));
            long deadline = System.currentTimeMillis() + 5_000L;
            while (coordinator.snapshot().state() != KnowledgeStoreState.FAILED && System.currentTimeMillis() < deadline) Thread.sleep(20L);
            assertEquals(KnowledgeStoreState.FAILED, coordinator.snapshot().state());
            assertEquals("KNOWLEDGE_ASSET_MISSING", coordinator.snapshot().failureReason());
        } finally { coordinator.close(); }
    }
    private static KnowledgeStoreValidationResult verify(Context context, Path directory, KnowledgeBundleManifest manifest) {
        try (io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(context).directory(directory.toFile()).build()) {
            java.util.List<KnowledgeStoreMetadataEntity> values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
            return values.size() == 1 ? new KnowledgeStoreCompatibilityValidator().validate(manifest, values.get(0)) : KnowledgeStoreValidationResult.failure("STORE_METADATA_COUNT_INVALID");
        } catch (Exception error) { return KnowledgeStoreValidationResult.failure("STORE_OPEN_FAILED"); }
    }
}
