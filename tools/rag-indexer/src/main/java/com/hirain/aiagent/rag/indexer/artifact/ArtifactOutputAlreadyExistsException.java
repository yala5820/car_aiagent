package com.hirain.aiagent.rag.indexer.artifact;

/**
 * 输出目录已被其他成功构建或并发构建占用。
 *
 * <p>使用专用类型而非依赖异常文本，使 CLI 能返回稳定、无路径泄露的原因码；调用方仍不得覆盖已有 Bundle。</p>
 */
public final class ArtifactOutputAlreadyExistsException extends IllegalArgumentException {
    public ArtifactOutputAlreadyExistsException() {
        super("输出目录已存在，禁止覆盖");
    }
}
