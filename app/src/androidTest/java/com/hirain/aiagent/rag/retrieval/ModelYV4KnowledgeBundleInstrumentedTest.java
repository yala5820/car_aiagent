package com.hirain.aiagent.rag.retrieval;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hirain.aiagent.rag.config.RagRetrievalConfig;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.AndroidKnowledgeAssetSource;
import com.hirain.aiagent.rag.store.KnowledgeAssetInstaller;
import com.hirain.aiagent.rag.store.KnowledgeInstallPlan;
import com.hirain.aiagent.rag.store.KnowledgeInstallResult;
import com.hirain.aiagent.rag.store.KnowledgeStorageLayout;
import com.hirain.aiagent.rag.store.KnowledgeStoreValidationResult;
import com.hirain.aiagent.rag.store.MyObjectBox;
import com.hirain.aiagent.rag.store.ObjectBoxKnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

/**
 * 只在本机存在 V4 候选时执行：验证真实资料仍以 TEST_ONLY 方式装入 androidTest APK，
 * 并在 Android ObjectBox Runtime 上完成 Scope、BM25 和 Dense 的最小闭环。
 */
public class ModelYV4KnowledgeBundleInstrumentedTest {
    private static final String ASSET_ROOT = "rag/model_y_v4_candidate";
    private static final VehicleProfile MODEL_Y_2026_CN_RWD =
            new VehicleProfile("MODEL_Y", "2026", "CN", "2026_REFRESH", "RWD", 0L);

    @Test
    public void installsAndSearchesLocalV4CandidateWithTrustedModelYScope() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Assume.assumeTrue("本机未生成 V4 TEST_ONLY 候选，跳过真实资料设备验收", hasV4Asset(test));

        File root = new File(target.getCacheDir(), "rag-model-y-v4-" + System.nanoTime());
        assertTrue(root.mkdirs());
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(
                new KnowledgeStorageLayout(root.toPath()),
                (directory, bytes) -> true,
                (directory, manifest) -> verify(target, directory, manifest));
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(
                new AndroidKnowledgeAssetSource(test.getAssets()), ASSET_ROOT,
                MODEL_Y_2026_CN_RWD, "model-y-v4-instrumented"));
        assertTrue(result.installed());
        assertTrue(installer.recoverActive().reused());

        try (io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(target)
                .directory(new KnowledgeStorageLayout(root.toPath()).versionDirectory(result.manifest().bundleVersion()).toFile()).build();
             ObjectBoxKnowledgeStoreGateway gateway = new ObjectBoxKnowledgeStoreGateway(store)) {
            List<RankedCandidate> lexical = new ObjectBoxLexicalSearcher(
                    new MetadataEligibilityPolicy(), RagRetrievalConfig.v1()).search(
                    gateway, new CjkLatinLexicalAnalyzer().analyze("北京服务中心"), MODEL_Y_2026_CN_RWD, 5);
            assertFalse(lexical.isEmpty());

            KnowledgeChunkEntity reference = store.boxFor(KnowledgeChunkEntity.class).query(
                    KnowledgeChunkEntity_.chunkLevel.equal("CHILD")).build().findFirst();
            assertTrue(reference != null && reference.embedding != null && reference.embedding.length == 1024);
            List<DenseCandidate> dense = new ObjectBoxDenseSearcher(new MetadataEligibilityPolicy())
                    .search(gateway, reference.embedding, MODEL_Y_2026_CN_RWD, 5);
            assertFalse(dense.isEmpty());
        }
    }

    private static boolean hasV4Asset(Context context) {
        try (InputStream ignored = context.getAssets().open(ASSET_ROOT + "/manifest.json")) {
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static KnowledgeStoreValidationResult verify(Context context, Path directory,
                                                          com.hirain.aiagent.rag.store.KnowledgeBundleManifest manifest) {
        try (io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(context).directory(directory.toFile()).build()) {
            List<KnowledgeStoreMetadataEntity> metadata = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
            return metadata.size() == 1
                    ? new com.hirain.aiagent.rag.store.KnowledgeStoreCompatibilityValidator().validate(manifest, metadata.get(0))
                    : KnowledgeStoreValidationResult.failure("STORE_METADATA_COUNT_INVALID");
        } catch (Exception error) {
            return KnowledgeStoreValidationResult.failure("STORE_OPEN_FAILED");
        }
    }
}
