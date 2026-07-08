package com.hirain.aiagent.core;

import com.hirain.aiagent.core.preprocessor.ContextExtraPreProcessor;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContextExtraPreProcessorTest {
    @Test
    public void prepare_hybridFirstIterationReturnsContextMessage() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "HYBRID_EXTRA_CONTEXT");
        data.put("context_rendered_extra", "【运行时上下文】\n- requestId: req-1");
        AgentLoopContext ctx = new AgentLoopContext("打开空调", "chat", data);

        List<ChatMessage> messages = new ContextExtraPreProcessor().prepare(ctx);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).toString().contains("【运行时上下文】"));
    }

    @Test
    public void prepare_observeOnlyReturnsNoMessages() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "OBSERVE_ONLY");
        data.put("context_rendered_extra", "【运行时上下文】");
        AgentLoopContext ctx = new AgentLoopContext("你好", "chat", data);

        assertTrue(new ContextExtraPreProcessor().prepare(ctx).isEmpty());
    }

    @Test
    public void prepare_secondIterationReturnsNoMessages() {
        Map<String, Object> data = new HashMap<>();
        data.put("context_mode", "HYBRID_EXTRA_CONTEXT");
        data.put("context_rendered_extra", "【运行时上下文】");
        AgentLoopContext ctx = new AgentLoopContext("打开空调", "chat", data);
        ctx.setIteration(1);

        assertTrue(new ContextExtraPreProcessor().prepare(ctx).isEmpty());
    }

    @Test
    public void prepare_fullContextModeAlsoInjectsOnFirstIteration() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("context_mode", "FULL_CONTEXT");
        data.put("context_rendered_extra", "【运行时上下文】");
        AgentLoopContext ctx = new AgentLoopContext("你好", "chat", data);

        java.util.List<dev.langchain4j.data.message.ChatMessage> messages =
                new ContextExtraPreProcessor().prepare(ctx);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).toString().contains("【运行时上下文】"));
    }
}
