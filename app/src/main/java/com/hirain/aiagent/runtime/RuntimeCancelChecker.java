package com.hirain.aiagent.runtime;

/**
 * Runtime 内部取消检查器。
 * <p>
 * 设计原因：Context 构建发生在 AgentRuntime.execute(session) 内部，
 * Service 层无法在 Context build 完成后、AgentLoop 启动前插入检查。
 */
@FunctionalInterface
public interface RuntimeCancelChecker {
    boolean isCancelled(RequestSession session);

    static RuntimeCancelChecker neverCancelled() {
        return session -> false;
    }
}
