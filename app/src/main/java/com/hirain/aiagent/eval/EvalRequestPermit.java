package com.hirain.aiagent.eval;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已进入 Eval 隔离环境的一次 TEXT 请求凭据。
 * close 幂等，保证取消、超时与 worker finally 重叠时不会错误递减 in-flight。
 */
public final class EvalRequestPermit implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Runnable onClose;
    EvalRequestPermit(Runnable onClose) { this.onClose = onClose; }
    @Override public void close() { if (closed.compareAndSet(false, true)) onClose.run(); }
}
