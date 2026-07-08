package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

/**
 * Context 片段提供者接口 — 每个 Provider 负责构建一个 {@link ContextSection}。
 */
public interface ContextProvider {

    /** Provider 名称，用于诊断和 Trace。 */
    String name();

    /** Provider 生成的 section 类型。 */
    ContextSectionType type();

    /**
     * 构建上下文片段。
     *
     * @param session 当前请求快照
     * @param input   构建期依赖容器
     * @return provider 结果（success / fallback / failure）
     * @throws ContextBuildException 不可恢复的异常
     */
    ContextProviderResult provide(RequestSession session, ContextBuildInput input)
            throws ContextBuildException;
}
