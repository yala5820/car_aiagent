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
        manager.installStarted();
        KnowledgeInstallResult result = installer.install(new KnowledgeInstallPlan(new AndroidKnowledgeAssetSource(context.getAssets()), ASSET_ROOT, profile, UUID.randomUUID().toString().replace("-", "")));
        if (result.installed() || result.reused()) {
            // 恢复指针与 APK Asset 很可能指向同一版本。必须等最终结果确定后只打开一次，
            // 否则对同一目录重复创建 BoxStore 会留下未被 Manager 接管的实例，
            // Android Finalizer 回收该实例时可能阻塞并触发 FinalizerWatchdog 杀死进程。
            activate(result);
            return;
        }
        if (recovered.reused()) {
            // 新 Asset 安装失败时仍恢复上次已验证的活动库；随后记录本轮安装失败，
            // 使知识能力保持 READY，同时保留可诊断的稳定失败原因码。
            activate(recovered);
        }
        manager.installFailed(result.reasonCode());
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
