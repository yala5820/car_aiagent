package com.hirain.aiagent.rag.indexer.artifact;

import java.nio.file.Path;
import java.util.Set;

/** V1 交付目录的唯一允许文件集合；Report 保留离线审计，但不得随 Android Asset 单独覆盖。 */
public final class BundleLayout {
    public static final String DATA_FILE = "data.mdb";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String REPORT_FILE = "build-report.json";
    /** ObjectBox 打开 Store 时的运行时锁；它不是 Bundle 协议文件，关闭验证后必须清理。 */
    public static final String RUNTIME_LOCK_FILE = "lock.mdb";
    public static final Set<String> REQUIRED_FILES = Set.of(DATA_FILE, MANIFEST_FILE, REPORT_FILE);
    public static Path data(Path root) { return root.resolve(DATA_FILE); }
    public static Path manifest(Path root) { return root.resolve(MANIFEST_FILE); }
    public static Path report(Path root) { return root.resolve(REPORT_FILE); }
    public static Path runtimeLock(Path root) { return root.resolve(RUNTIME_LOCK_FILE); }
    private BundleLayout() { }
}
