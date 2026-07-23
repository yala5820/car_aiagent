package com.hirain.aiagent.rag.store;

import android.os.StatFs;
import java.nio.file.Path;

/** 使用 Android StatFs 的生产空间检查，所有预留策略由安装器集中计算。 */
public final class AndroidStorageCapacityChecker implements StorageCapacityChecker {
    @Override public boolean hasCapacity(Path directory, long requiredBytes) {
        java.io.File existing = directory.toFile();
        while (!existing.exists() && existing.getParentFile() != null) existing = existing.getParentFile();
        StatFs stat = new StatFs(existing.getAbsolutePath());
        return stat.getAvailableBytes() >= requiredBytes;
    }
}
