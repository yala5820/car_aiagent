package com.hirain.aiagent.context;

/**
 * Context 使用的取消窄接口。
 * <p>
 * 设计原因：Context 模块不依赖 {@code ActiveRequestRegistry} 具体类。
 * AgentRuntime 用 {@code contextSession -> runtimeCancelChecker.isCancelled(contextSession)} 构造此适配器。
 */
@FunctionalInterface
public interface ContextCancelChecker {
    boolean isCancelled();

    static ContextCancelChecker neverCancelled() {
        return () -> false;
    }
}
