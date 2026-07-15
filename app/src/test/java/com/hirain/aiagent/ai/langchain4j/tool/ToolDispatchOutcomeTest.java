package com.hirain.aiagent.ai.langchain4j.tool;

import org.junit.Test;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolDispatchOutcomeTest {

    @Test
    public void successAndArgumentFailureHaveStableStructuredState() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(new DemoTool());

        ToolDispatchOutcome success = registry.dispatchWithOutcome(request("{\"arg0\":\"ok\"}"));
        ToolDispatchOutcome failure = registry.dispatchWithOutcome(request("{}"));

        assertTrue(success.registered());
        assertTrue(success.argumentParseSuccess());
        assertTrue(success.invokeSuccess());
        assertTrue(success.dispatchSuccess());
        assertFalse(failure.argumentParseSuccess());
        assertFalse(failure.dispatchSuccess());
    }

    @Test
    public void unregisteredToolIsStructuredFailure() {
        ToolDispatchOutcome outcome = new ToolRegistry().dispatchWithOutcome(
                ToolExecutionRequest.builder().id("1").name("missing").arguments("{}").build());
        assertFalse(outcome.registered());
        assertFalse(outcome.dispatchSuccess());
    }

    private static ToolExecutionRequest request(String arguments) {
        return ToolExecutionRequest.builder().id("1").name("demo_echo")
                .arguments(arguments).build();
    }

    public static final class DemoTool {
        @Tool(name = "demo_echo")
        public String echo(String value) {
            return value;
        }
    }
}
