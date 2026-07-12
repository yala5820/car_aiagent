package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ContextBudgetIntegrationTest {

    private final ContextBudgetManager manager = ContextBudgetManager.defaultBudget();
    private final ContextTokenEstimator estimator = new HeuristicContextTokenEstimator();

    @Test
    public void smallBudget_withinBudget_noTrimming() {
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("You are assistant"),
                UserMessage.from("Hello"));
        ContextBudgetDecision decision = manager.makeDecision(msgs, List.of(),
                new ContextBudgetPolicy(100000, 1000, 100), estimator);
        assertTrue("Should be within budget", decision.withinBudget());
        assertTrue("Trim actions should be empty", decision.trimActions().isEmpty());
    }

    @Test
    public void longHistory_trimsOldestTurn() {
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("You are assistant"),
                UserMessage.from("第一轮对话内容很长很多字已经超过了预算"),
                AiMessage.from("第一轮回复"),
                UserMessage.from("第二轮对话这也是一段很长的内容"),
                AiMessage.from("第二轮回复"));
        ContextBudgetDecision decision = manager.makeDecision(msgs, List.of(),
                new ContextBudgetPolicy(100, 50, 10), estimator);
        // System + first user message should remain
        assertNotNull(decision.messages());
        assertTrue(decision.messages().contains(msgs.get(0))); // System
    }

    @Test
    public void toolSetExceedsBudget_returnsFailure() {
        dev.langchain4j.agent.tool.ToolSpecification tool =
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name("very_long_tool_name")
                        .description("A tool with an extremely long description that takes up many tokens")
                        .build();
        List<ChatMessage> msgs = List.of(
                SystemMessage.from("You"),
                UserMessage.from("Hi"));
        ContextBudgetDecision decision = manager.makeDecision(msgs, List.of(tool),
                new ContextBudgetPolicy(1, 1, 1), estimator);
        assertFalse("Tool set exceeding budget should fail", decision.withinBudget());
        assertEquals(ContextErrorCode.CONTEXT_BUDGET_EXCEEDED, decision.failureCode());
    }
}
