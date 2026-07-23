package com.hirain.aiagent.rag.indexer.pipeline;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消是协作信号而非强杀线程：调用方应在开始新批次、发起网络调用和发布前主动检查，
 * 以保留 Work Cache 并避免覆盖已有 Output。
 */
public final class BuildCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new BuildCancelledException();
        }
    }

    public static final class BuildCancelledException extends RuntimeException {
        private BuildCancelledException() {
        }
    }
}
