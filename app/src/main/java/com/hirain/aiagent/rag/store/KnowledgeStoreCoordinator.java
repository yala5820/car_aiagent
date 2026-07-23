package com.hirain.aiagent.rag.store;

import android.content.Context;
import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service 使用的异步入口：安装与切换严格串行；无 Asset 或安装失败仅使 RAG 不可用，
 * 不阻塞普通对话、车控及 Service 生命周期。
 */
public final class KnowledgeStoreCoordinator implements AutoCloseable {
    private static final String ASSET_ROOT = "rag/knowledge_db";
    private final Context context;
    private final KnowledgeStorageLayout layout;
    private final KnowledgeStoreManager manager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public KnowledgeStoreCoordinator(Context context, KnowledgeStoreManager manager) {
        this.context = context.getApplicationContext(); this.manager = manager; this.layout = new KnowledgeStorageLayout(this.context.getFilesDir().toPath());
    }
    public void initializeAsync(VehicleProfile profile) {
        if (closed.get()) return;
        executor.execute(() -> install(profile));
    }
    public KnowledgeStoreSnapshot snapshot() { return manager.snapshot(); }
    public KnowledgeStoreLease<KnowledgeStoreGateway> acquire() { return manager.acquire(); }
    private void install(VehicleProfile profile) {
        if (closed.get()) return;
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(layout, new AndroidStorageCapacityChecker(), this::verifyCandidate);
        KnowledgeInstallResult recovered = installer.recoverActive();
        if (recovered.reused()) activate(recovered);
        manager.installStarted();
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(new AndroidKnowledgeAssetSource(context.getAssets()), ASSET_ROOT, profile, UUID.randomUUID().toString().replace("-", "")));
        if (!result.installed() && !result.reused()) { manager.installFailed(result.reasonCode()); return; }
        activate(result);
    }
    private void activate(KnowledgeInstallResult result) {
        try {
            Path directory = layout.versionDirectory(result.manifest().bundleVersion());
            io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(context).directory(directory.toFile()).build();
            manager.activate(new ObjectBoxKnowledgeStoreGateway(store), result.manifest().bundleVersion(), result.manifest().knowledgeScopeId());
        } catch (Exception error) { manager.installFailed("KNOWLEDGE_STORE_UNAVAILABLE"); }
    }
    private KnowledgeStoreValidationResult verifyCandidate(Path directory, KnowledgeBundleManifest manifest) {
        try (io.objectbox.BoxStore store = MyObjectBox.builder().androidContext(context).directory(directory.toFile()).build()) {
            java.util.List<KnowledgeStoreMetadataEntity> values = store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();
            if (values.size() != 1) return KnowledgeStoreValidationResult.failure("STORE_METADATA_COUNT_INVALID");
            return new KnowledgeStoreCompatibilityValidator().validate(manifest, values.get(0));
        } catch (Exception error) { return KnowledgeStoreValidationResult.failure("STORE_OPEN_FAILED"); }
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) { executor.shutdownNow(); manager.close(); } }
}
