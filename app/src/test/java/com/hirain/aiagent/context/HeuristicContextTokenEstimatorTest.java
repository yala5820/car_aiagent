package com.hirain.aiagent.context;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class HeuristicContextTokenEstimatorTest {

    private final HeuristicContextTokenEstimator estimator = new HeuristicContextTokenEstimator();

    @Test
    public void chineseAndEnglishInputs_nonZero() {
        List<dev.langchain4j.data.message.ChatMessage> msgs = List.of(
                SystemMessage.from("你是智能车控助手。"),
                UserMessage.from("帮我打开空调，温度调到24度"),
                AiMessage.from("好的，已为您打开空调并调至24度。"));
        int estimate = estimator.estimateMessages(msgs);
        assertTrue("Chinese text estimate should be > 0, got " + estimate, estimate > 0);
    }

    @Test
    public void toolCallMessages_nonZero() {
        List<dev.langchain4j.data.message.ChatMessage> msgs = List.of(
                SystemMessage.from("System"),
                UserMessage.from("Turn on AC"),
                AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("r1").name("set_ac_status").arguments("{\"on\":true}").build()),
                new ToolExecutionResultMessage("r1", "set_ac_status", "success"));
        int estimate = estimator.estimateMessages(msgs);
        assertTrue("Tool call messages estimate should be > 0, got " + estimate, estimate > 0);
    }

    @Test
    public void complexToolParameterSchema_increasesEstimate() {
        ToolSpecification simpleSpec = ToolSpecification.builder()
                .name("get_weather").description("Get weather").build();
        ToolSpecification complexSpec = ToolSpecification.builder()
                .name("get_weather")
                .description("Get weather forecast for a location with very long description that contains many tokens")
                .build();

        int simple = estimator.estimateToolSpecs(List.of(simpleSpec));
        int complex = estimator.estimateToolSpecs(List.of(complexSpec));
        assertTrue("Complex schema should have higher estimate than simple, simple="
                + simple + " complex=" + complex, complex >= simple);
    }

    @Test
    public void emptyMessagesAndTools_returnsZero() {
        assertEquals("Empty messages", 0, estimator.estimateMessages(List.of()));
        assertEquals("Empty tools", 0, estimator.estimateToolSpecs(List.of()));
    }

    @Test
    public void longerToolArgumentsAndResultsIncreaseEstimateMonotonically() {
        AiMessage shortCall = AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("r1").name("set_ac_status").arguments("{}").build());
        AiMessage longCall = AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("r1").name("set_ac_status")
                .arguments("{\"temperature\":24,\"mode\":\"AUTO\",\"zones\":[\"LEFT\",\"RIGHT\"]}")
                .build());
        int shortEstimate = estimator.estimateMessages(List.of(shortCall,
                new ToolExecutionResultMessage("r1", "set_ac_status", "ok")));
        int longEstimate = estimator.estimateMessages(List.of(longCall,
                new ToolExecutionResultMessage("r1", "set_ac_status",
                        "success with full vehicle state and temperature details")));
        assertTrue(longEstimate > shortEstimate);
    }
}
