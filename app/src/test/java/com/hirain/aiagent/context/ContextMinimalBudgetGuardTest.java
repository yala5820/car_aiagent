package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 最小预算守卫测试 — 验证组装结果中 ContextBudgetReport 的正确性。
 * 裁剪和压缩已停用，仅保留超限失败。
 */
public class ContextMinimalBudgetGuardTest {

    @Test
    public void smallRequest_withinBudget() {
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("You are a helpful assistant"),
                UserMessage.from("Hi"));
        List<ToolSpecification> tools = List.of();

        ContextBudgetPolicy policy = ModelContextWindowProfiles.qwenTurboDemo();
        int estimated = new HeuristicContextTokenEstimator().estimateTotal(msgs, tools);
        ContextBudgetReport report = new ContextBudgetReport(
                estimated, policy.maxInputTokens(), estimated <= policy.maxInputTokens());

        assertTrue("Small request should be within budget", report.withinBudget());
        assertTrue(estimated <= policy.maxInputTokens());
    }

    @Test
    public void oversizedRequest_exceedsBudget() {
        String veryLong = "x".repeat(100000);
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("You are a helpful assistant"),
                UserMessage.from("Hi " + veryLong));
        List<ToolSpecification> tools = List.of();

        ContextBudgetPolicy tinyPolicy = new ContextBudgetPolicy(200, 100, 50);
        int estimated = new HeuristicContextTokenEstimator().estimateTotal(msgs, tools);
        ContextBudgetReport report = new ContextBudgetReport(
                estimated, tinyPolicy.maxInputTokens(), estimated <= tinyPolicy.maxInputTokens());

        assertFalse("Oversized request should exceed budget", report.withinBudget());
        assertTrue(estimated > tinyPolicy.maxInputTokens());
    }
}
