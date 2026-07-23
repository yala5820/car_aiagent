package com.hirain.aiagent.rag.indexer.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 敏感中间产物限定在 work/runs/runId，不能与正式 output 或 Corpus Root 重叠。 */
public final class BuildWorkspace {
    public Path create(Path workRoot, String runId) throws IOException {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("runId 非法");
        Path run = workRoot.resolve("runs").resolve(runId); if (Files.exists(run)) throw new IllegalArgumentException("runId 已存在，拒绝覆盖中间产物");
        return Files.createDirectories(run);
    }
}
