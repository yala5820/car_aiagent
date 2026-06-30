package com.hirain.aiagent.core.component;

import com.hirain.aiagent.core.AgentLoopContext;
import com.hirain.aiagent.core.SafetyVerdict;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 安全审查 — 在工具执行前判断该调用是否被允许。
 * <p>
 * 在车载场景中用于关键安全规则，例如：
 * <ul>
 *   <li>车速超过 5 km/h 时不允许解锁车门</li>
 *   <li>车辆行驶中不允许调节方向盘位置</li>
 * </ul>
 */
@FunctionalInterface
public interface SafetyGuard {

    /**
     * 审查工具调用的安全性。
     * @param request 待执行的工具调用请求
     * @param ctx     当前执行上下文（含车辆状态等）
     * @return ALLOW 放行 / VETO("原因") 否决
     */
    SafetyVerdict evaluate(ToolExecutionRequest request, AgentLoopContext ctx);
}
