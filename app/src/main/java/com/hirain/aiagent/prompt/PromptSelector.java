package com.hirain.aiagent.prompt;

import java.util.Map;

/**
 * Prompt 选择器 — 根据上下文选择最合适的 Prompt 模板。
 * <p>
 * 调用方收集当前上下文（场景类型、用户意图、车辆状态等），
 * 传入 {@link #select(Map)} 方法获取模板名称，然后通过
 * {@link PromptManager#render(String, Map)} 渲染。
 * <p>
 * 内置实现：
 * <ul>
 *   <li>{@link DefaultPromptSelector} — 始终返回 {@link PromptConstants#SYSTEM_ASSISTANT_DEFAULT}</li>
 *   <li>{@link ScenePromptSelector} — 根据场景类型切换</li>
 *   <li>{@link CompositePromptSelector} — 链式组合多个 Selector</li>
 * </ul>
 */
@FunctionalInterface
public interface PromptSelector {

    /**
     * 根据上下文选择模板名称。
     *
     * @param context 上下文键值对，不可变快照
     * @return 模板路径（相对于 assets/prompts/，不含 .txt 后缀）
     */
    String select(Map<String, Object> context);

    // ── 内置实现 ──

    /** 始终返回默认助手提示词 */
    final class DefaultPromptSelector implements PromptSelector {
        @Override
        public String select(Map<String, Object> context) {
            return PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
        }
    }

    /** 根据场景类型选择提示词 */
    final class ScenePromptSelector implements PromptSelector {
        @Override
        public String select(Map<String, Object> context) {
            String sceneType = (String) context.get("scene_type");
            if (sceneType != null && !sceneType.isEmpty()
                    && !"其他".equals(sceneType) && !"无效场景".equals(sceneType)) {
                return PromptConstants.SYSTEM_ASSISTANT_SCENE;
            }
            return PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
        }
    }

    /** 按优先级链式组合多个 Selector */
    final class CompositePromptSelector implements PromptSelector {
        private final PromptSelector[] selectors;

        public CompositePromptSelector(PromptSelector... selectors) {
            this.selectors = selectors;
        }

        @Override
        public String select(Map<String, Object> context) {
            for (PromptSelector selector : selectors) {
                String result = selector.select(context);
                if (result != null) return result;
            }
            return PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
        }
    }
}
