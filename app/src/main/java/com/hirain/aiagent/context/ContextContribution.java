package com.hirain.aiagent.context;

import java.util.Map;

/**
 * Context Contribution 基础契约 — Provider 输出的结构化数据单元。
 * <p>
 * 每个 Contribution 必须显式声明 sourceKey、visibility、trustLevel、
 * priority、lifecycle、required 和稳定 metadata。
 * 不使用通用 {@code Object payload}；文本、消息、工具必须由不同强类型承载。
 */
public interface ContextContribution {

    /** 贡献来源的唯一标识，如 "runtime"、"prompt"、"intent"。 */
    String sourceKey();

    /** 模型可见性。 */
    ContextVisibility visibility();

    /** 信任级别。 */
    ContextTrustLevel trustLevel();

    /** 优先级（用于预算裁剪）。 */
    ContextPriority priority();

    /** 生命周期（请求级静态或每轮动态）。 */
    ContextLifecycle lifecycle();

    /** 若为 true，缺少此 Contribution 应导致请求失败。 */
    boolean required();

    /** Provider 名称。 */
    String providerName();

    /** 稳定的元数据（不可变 Map）。 */
    Map<String, Object> metadata();
}
