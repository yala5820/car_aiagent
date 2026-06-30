package com.hirain.aiagent.core.component;

import com.hirain.aiagent.core.AgentLoopContext;

/**
 * 后处理器 — 在 LLM 返回文本（无工具调用）后对输出进行转换。
 * <p>
 * 用于追加 VL 警告后缀、合并硬编码场景动作、格式化输出等。
 */
@FunctionalInterface
public interface PostProcessor {

    /**
     * 处理 LLM 输出文本。
     * @param llmOutput LLM 原始输出
     * @param ctx       当前执行上下文
     * @return 处理后的文本
     */
    String process(String llmOutput, AgentLoopContext ctx);
}
