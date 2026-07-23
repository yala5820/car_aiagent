package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.model.VehicleProfile;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 启动恢复必须重新校验 active Store；已损坏数据不能因旧指针而被误激活。 */
public class KnowledgeStoreRecoveryTest {
    @Test public void rejectsCorruptedCommittedStoreDuringRecovery() throws Exception {
        Path source = Path.of("..", "rag-schema", "test-fixtures", "development-v1", "candidate-v1");
        byte[] manifest = Files.readAllBytes(source.resolve("manifest.json"));
        byte[] data = Files.readAllBytes(source.resolve("data.mdb"));
        KnowledgeAssetSource assets = name -> { if (name.endsWith("manifest.json")) return new ByteArrayInputStream(manifest); if (name.endsWith("data.mdb")) return new ByteArrayInputStream(data); throw new FileNotFoundException(name); };
        Path files = Files.createTempDirectory("rag-recover-");
        KnowledgeAssetInstaller installer = new KnowledgeAssetInstaller(new KnowledgeStorageLayout(files), (directory, bytes) -> true, (directory, value) -> KnowledgeStoreValidationResult.success());
        VehicleProfile profile = new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0);
        assertTrue(installer.install(new KnowledgeInstallPlan(assets, "bundle", profile, "first")).installed());
        Files.write(files.resolve("rag/stores/0.0.1/data.mdb"), new byte[] {1});
        assertEquals("BUNDLE_DATA_FILE_SIZE_MISMATCH", installer.recoverActive().reasonCode());
    }
}
