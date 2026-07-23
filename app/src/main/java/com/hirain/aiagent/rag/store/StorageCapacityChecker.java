package com.hirain.aiagent.rag.store;

import java.nio.file.Path;

/** 安装前空间检查可替换，测试不依赖设备实际磁盘余量。 */
public interface StorageCapacityChecker {
    boolean hasCapacity(Path directory, long requiredBytes);
}
