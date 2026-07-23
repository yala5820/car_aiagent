package com.hirain.aiagent.rag.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.rag.model.VehicleProfile;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Bundle 的事务安装器：先在同文件系统 staging 完整验证，再移动版本目录并最后提交活动指针。
 * 任一失败都不会覆盖既有版本目录或 active 指针，因此上层可以继续使用旧 Store。
 */
public final class KnowledgeAssetInstaller {
    private static final long SAFETY_MARGIN_BYTES = 512 * 1024L;
    private final KnowledgeStorageLayout layout;
    private final StorageCapacityChecker capacity;
    private final FileDigestVerifier digestVerifier;
    private final KnowledgeBundleManifestParser manifestParser;
    private final KnowledgeStoreCandidateVerifier storeVerifier;

    public KnowledgeAssetInstaller(KnowledgeStorageLayout layout, StorageCapacityChecker capacity) {
        this(layout, capacity, (directory, manifest) -> new KnowledgeStoreCompatibilityValidator().validateStoreDirectory(directory, manifest));
    }
    public KnowledgeAssetInstaller(KnowledgeStorageLayout layout, StorageCapacityChecker capacity, KnowledgeStoreCandidateVerifier storeVerifier) {
        this.layout = layout; this.capacity = capacity; this.storeVerifier = storeVerifier; this.digestVerifier = new FileDigestVerifier(); this.manifestParser = new KnowledgeBundleManifestParser();
    }

    public KnowledgeInstallResult install(KnowledgeInstallPlan plan) {
        Path staging = null;
        try {
            String root = plan.assetRoot();
            KnowledgeBundleManifest manifest = manifestParser.parse(readText(plan.assets(), root + "/manifest.json"));
            if (!matchesScope(manifest, plan.vehicleProfile())) return KnowledgeInstallResult.failed("KNOWLEDGE_SCOPE_MISMATCH");
            Path target = layout.versionDirectory(manifest.bundleVersion());
            if (Files.exists(target)) return validateExisting(target, manifest);
            Files.createDirectories(layout.staging()); Files.createDirectories(layout.stores());
            if (!capacity.hasCapacity(layout.root(), manifest.dataFile().sizeBytes() + SAFETY_MARGIN_BYTES)) return KnowledgeInstallResult.failed("KNOWLEDGE_STORAGE_INSUFFICIENT");
            staging = layout.stagingDirectory(plan.installId());
            if (Files.exists(staging)) return KnowledgeInstallResult.failed("INSTALL_STAGING_ALREADY_EXISTS");
            Files.createDirectories(staging);
            writeText(staging.resolve("manifest.json"), readText(plan.assets(), root + "/manifest.json"));
            copyAndSync(plan.assets(), root + "/data.mdb", staging.resolve("data.mdb"));
            KnowledgeStoreValidationResult dataCheck = digestVerifier.verify(staging.resolve("data.mdb"), manifest.dataFile());
            if (!dataCheck.valid()) return KnowledgeInstallResult.failed(dataCheck.reasonCode());
            KnowledgeStoreValidationResult storeCheck = storeVerifier.verify(staging, manifest);
            if (!storeCheck.valid()) return KnowledgeInstallResult.failed(storeCheck.reasonCode());
            move(staging, target);
            writePointer(new ActiveBundlePointer(manifest.bundleVersion(), manifest.dataFile().sha256(), manifest.schemaFingerprint()));
            return KnowledgeInstallResult.installed(manifest);
        } catch (java.io.FileNotFoundException error) { return KnowledgeInstallResult.failed("KNOWLEDGE_ASSET_MISSING");
        } catch (java.nio.file.NoSuchFileException error) { return KnowledgeInstallResult.failed("KNOWLEDGE_ASSET_MISSING");
        } catch (IllegalArgumentException error) { return KnowledgeInstallResult.failed(error.getMessage());
        } catch (Exception error) { return KnowledgeInstallResult.failed("KNOWLEDGE_INSTALL_FAILED"); }
    }

    /** 启动恢复只信任已提交的 active.json；遗留 tmp/staging 永不覆盖当前活动版本。 */
    public KnowledgeInstallResult recoverActive() {
        try {
            if (!Files.isRegularFile(layout.activePointer())) return KnowledgeInstallResult.failed("KNOWLEDGE_ACTIVE_POINTER_MISSING");
            JsonObject pointerJson = JsonParser.parseString(new String(Files.readAllBytes(layout.activePointer()), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (pointerJson.keySet().size() != 3 || !pointerJson.has("bundleVersion") || !pointerJson.has("dataSha256") || !pointerJson.has("schemaFingerprint")) return KnowledgeInstallResult.failed("KNOWLEDGE_ACTIVE_POINTER_INVALID");
            ActiveBundlePointer pointer = new ActiveBundlePointer(pointerJson.get("bundleVersion").getAsString(), pointerJson.get("dataSha256").getAsString(), pointerJson.get("schemaFingerprint").getAsString());
            Path directory = layout.versionDirectory(pointer.bundleVersion());
            KnowledgeBundleManifest manifest = manifestParser.parse(new String(Files.readAllBytes(directory.resolve("manifest.json")), java.nio.charset.StandardCharsets.UTF_8));
            if (!pointer.dataSha256().equals(manifest.dataFile().sha256()) || !pointer.schemaFingerprint().equals(manifest.schemaFingerprint())) return KnowledgeInstallResult.failed("KNOWLEDGE_ACTIVE_POINTER_MISMATCH");
            KnowledgeStoreValidationResult digest = digestVerifier.verify(directory.resolve("data.mdb"), manifest.dataFile());
            if (!digest.valid()) return KnowledgeInstallResult.failed(digest.reasonCode());
            KnowledgeStoreValidationResult store = storeVerifier.verify(directory, manifest);
            return store.valid() ? KnowledgeInstallResult.reused(manifest) : KnowledgeInstallResult.failed(store.reasonCode());
        } catch (IllegalArgumentException error) { return KnowledgeInstallResult.failed(error.getMessage());
        } catch (Exception error) { return KnowledgeInstallResult.failed("KNOWLEDGE_ACTIVE_STORE_INVALID"); }
    }

    private KnowledgeInstallResult validateExisting(Path target, KnowledgeBundleManifest incoming) {
        try {
            KnowledgeBundleManifest current = manifestParser.parse(new String(Files.readAllBytes(target.resolve("manifest.json")), java.nio.charset.StandardCharsets.UTF_8));
            if (!current.bundleVersion().equals(incoming.bundleVersion()) || !current.dataFile().sha256().equals(incoming.dataFile().sha256()) || !current.schemaFingerprint().equals(incoming.schemaFingerprint())) return KnowledgeInstallResult.failed("BUNDLE_VERSION_REUSED_WITH_DIFFERENT_CONTENT");
            KnowledgeStoreValidationResult result = digestVerifier.verify(target.resolve("data.mdb"), current.dataFile());
            if (!result.valid()) return KnowledgeInstallResult.failed(result.reasonCode());
            // 上次可能已完成同文件系统移动、但在写 active 指针前进程中断；重新验证后才允许补提交。
            writePointer(new ActiveBundlePointer(current.bundleVersion(), current.dataFile().sha256(), current.schemaFingerprint()));
            return KnowledgeInstallResult.reused(current);
        } catch (Exception error) { return KnowledgeInstallResult.failed("BUNDLE_EXISTING_VERSION_INVALID"); }
    }
    private void writePointer(ActiveBundlePointer pointer) throws Exception {
        Files.createDirectories(layout.root());
        JsonObject json = new JsonObject(); json.addProperty("bundleVersion", pointer.bundleVersion()); json.addProperty("dataSha256", pointer.dataSha256()); json.addProperty("schemaFingerprint", pointer.schemaFingerprint());
        writeText(layout.activePointerTemporary(), json.toString());
        move(layout.activePointerTemporary(), layout.activePointer());
    }
    private static boolean matchesScope(KnowledgeBundleManifest manifest, VehicleProfile profile) {
        return manifest.scope().get("vehicleModel").equals(profile.vehicleModel()) && manifest.scope().get("modelYear").equals(profile.modelYear()) && manifest.scope().get("region").equals(profile.region()) && manifest.scope().get("softwareVersion").equals(profile.softwareVersion()) && manifest.scope().get("configurationCode").equals(profile.configurationCode());
    }
    private static String readText(KnowledgeAssetSource source, String path) throws Exception { try (InputStream input = source.open(path)) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } }
    private static void copyAndSync(KnowledgeAssetSource source, String asset, Path destination) throws Exception { try (InputStream input = source.open(asset); BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))) { byte[] buffer = new byte[8192]; for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count); output.flush(); } try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) { channel.force(true); } }
    private static void writeText(Path destination, String value) throws Exception { Files.write(destination, value.getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) { channel.force(true); } }
    private static void move(Path source, Path destination) throws Exception { try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException error) { Files.move(source, destination); } }
}
