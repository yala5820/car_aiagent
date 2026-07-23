package com.hirain.aiagent.rag.indexer.artifact;

import java.nio.file.Path;

/** 发布成功只代表原子目录移动完成；激活 Android Asset 仍由后续跨端 Gate 负责。 */
public record ArtifactPublishResult(Path outputDirectory) { }
