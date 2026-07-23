package com.hirain.aiagent.rag.indexer.artifact;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;

/** 只接受已验证 staging Bundle；目标已存在或原子移动不可用时失败并保留原状。 */
public final class ArtifactPublisher {
    public ArtifactPublishResult publish(Path staging, Path output, boolean publishable) throws IOException {
        if (!publishable) throw new IllegalArgumentException("未通过发布门禁的 Bundle 不可发布");
        return moveVerifiedBundle(staging, output);
    }

    /**
     * 交付 TEST_ONLY 开发候选，但不改变其报告中的不可发布事实。
     *
     * <p>该方法只解决开发联调所需的完整目录交接，仍使用与正式发布相同的原子移动、禁止覆盖
     * 和三文件布局；调用方不得将该结果复制为 Android 正式 Asset。</p>
     */
    public ArtifactPublishResult publishDevelopmentCandidate(Path staging, Path output) throws IOException {
        return moveVerifiedBundle(staging, output);
    }

    private ArtifactPublishResult moveVerifiedBundle(Path staging, Path output) throws IOException {
        if (Files.exists(output)) throw new ArtifactOutputAlreadyExistsException();
        verifyLayout(staging); Path parent = output.getParent(); if (parent == null) throw new IllegalArgumentException("输出路径缺少父目录");
        Files.createDirectories(parent); FileStore stagingStore = Files.getFileStore(staging); FileStore outputStore = Files.getFileStore(parent);
        if (!stagingStore.equals(outputStore)) throw new IllegalArgumentException("staging 与 output 不在同一 FileStore");
        try { Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { throw new IllegalStateException("当前文件系统不支持 V1 原子目录发布", exception); }
        return new ArtifactPublishResult(output);
    }
    private static void verifyLayout(Path staging) throws IOException {
        if (!Files.isDirectory(staging)) throw new IllegalArgumentException("staging Bundle 不存在");
        HashSet<String> names = new HashSet<>(); try (var files = Files.list(staging)) { files.forEach(path -> names.add(path.getFileName().toString())); }
        if (!names.equals(BundleLayout.REQUIRED_FILES) || !Files.isRegularFile(BundleLayout.data(staging))
                || !Files.isRegularFile(BundleLayout.manifest(staging)) || !Files.isRegularFile(BundleLayout.report(staging))) throw new IllegalArgumentException("Bundle 文件布局非法");
    }
}
