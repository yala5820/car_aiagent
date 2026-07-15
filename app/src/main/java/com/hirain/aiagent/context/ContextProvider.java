package com.hirain.aiagent.context;

import com.hirain.aiagent.runtime.RequestSession;

/**
 * Context 提供者接口 — 每个 Provider 负责读取一个业务来源并输出强类型 Contribution。
 */
public interface ContextProvider {

    /** Provider 名称，用于诊断和 Trace。 */
    String name();

    /** Provider 的稳定生产来源键；旧测试 Provider 默认沿用名称。 */
    default String sourceKey() { return name(); }


    /**
     * Provider 数据的生命周期。
     * <p>
     * 默认返回 {@link ContextLifecycle#REQUEST_STATIC}。
     * 迭代级 Provider（SessionMemory、Vehicle、Time）应重写为 {@link ContextLifecycle#ITERATION_DYNAMIC}。
     */
    default ContextLifecycle lifecycle() {
        return ContextPolicies.isRegistered(sourceKey())
                ? ContextPolicies.source(sourceKey()).lifecycle()
                : ContextLifecycle.REQUEST_STATIC;
    }

    /**
     * 当前 Provider 在当前请求中是否为必需。
     * <p>
     * 必需 Provider 输出失败时，整个 prepare/assemble 应返回失败而非继续。
     * FALLBACK/FAILED 均终止。FALLBACK 只允许用于 optional Provider 的安全降级。
     *
     * @param session 当前请求快照
     * @param input   构建期依赖容器
     * @return true 表示失败时应中断流程
     */
    default boolean required(RequestSession session, ContextBuildInput input) {
        return ContextPolicies.isRegistered(sourceKey())
                && ContextPolicies.resolve(sourceKey(), session, input).required();
    }

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
