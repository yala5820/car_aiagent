package com.hirain.aiagent.intentrouter;

/**
 * 轻量意图标签器接口 — 为文本请求生成粗粒度 IntentResult。
 * <p>
 * 接口不依赖 Android Context、LLM、模型、tool registry 或 memory。
 * 判断错误不应影响请求执行，调用方应捕获异常并降级为 UNKNOWN。
 */
public interface IntentRouter {
    IntentResult route(String text, String sourceInputType);
}
