package com.hirain.aiagent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.trace.TestTraceSupport;
import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

public class MemoryCompressorTraceTest {

    @Test
    public void recordsMemoryCompressSpanWhenCompressionNotNeeded() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryCompressor compressor = new MemoryCompressor(new FakeChatModel("不应调用"));
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system"),
                UserMessage.from("你好"),
                AiMessage.from("你好"));

        List<ChatMessage> result = compressor.compress(messages, 100, recorder);
        session.close();

        assertSame(messages, result);
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_COMPRESS, data.getName());
        assertEquals("compress", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OPERATION)));
        assertEquals(false, data.getAttributes().get(AttributeKey.booleanKey(TraceAttributeKeys.MEMORY_COMPRESSED)));
    }

    @Test
    public void recordsMemoryCompressSpanWhenCompressionSucceeds() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryCompressor compressor = new MemoryCompressor(new FakeChatModel("用户喜欢 24 度空调"));
        List<ChatMessage> messages = longConversation();

        List<ChatMessage> result = compressor.compress(messages, 4500, recorder);
        session.close();

        assertTrue(result.size() < messages.size());
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_COMPRESS, data.getName());
        assertEquals(true, data.getAttributes().get(AttributeKey.booleanKey(TraceAttributeKeys.MEMORY_COMPRESSED)));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_PROMPT))
                .contains("用户：第 0 轮消息"));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OUTPUT))
                .contains("用户喜欢 24 度空调"));
    }

    @Test
    public void recordsMemoryCompressSpanWhenSummaryIsEmpty() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryCompressor compressor = new MemoryCompressor(new FakeChatModel("   "));
        List<ChatMessage> messages = longConversation();

        List<ChatMessage> result = compressor.compress(messages, 4500, recorder);
        session.close();

        assertSame(messages, result);
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_COMPRESS, data.getName());
        assertEquals(false, data.getAttributes().get(AttributeKey.booleanKey(TraceAttributeKeys.MEMORY_COMPRESSED)));
        assertEquals(0L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_OUTPUT_CHARS)).longValue());
    }

    @Test
    public void targetCompressionReplacesExistingSummaryAndPreservesProtectedTurns() {
        MemoryCompressor compressor = new MemoryCompressor(
                new FakeChatModel("合并后的唯一摘要"));
        List<ChatMessage> oldTurns = List.of(
                UserMessage.from("旧问题"), AiMessage.from("旧回答"));
        List<ChatMessage> protectedTurns = List.of(
                UserMessage.from("最近问题一"), AiMessage.from("最近回答一"),
                UserMessage.from("最近问题二"), AiMessage.from("最近回答二"));
        List<ChatMessage> original = new ArrayList<>();
        original.add(UserMessage.from("【对话摘要】旧摘要"));
        original.addAll(oldTurns);
        original.addAll(protectedTurns);
        MemoryCompactionPlan plan = new MemoryCompactionPlan(
                "session-1", 500, 900, "fingerprint", original,
                "旧摘要", oldTurns, protectedTurns);

        List<ChatMessage> result = compressor.compressForTarget(plan, null);

        assertEquals(5, result.size());
        assertEquals("【对话摘要】合并后的唯一摘要",
                ((UserMessage) result.get(0)).singleText());
        assertEquals(protectedTurns, result.subList(1, result.size()));
        long summaryCount = result.stream().filter(message -> message instanceof UserMessage
                && ((UserMessage) message).singleText().startsWith("【对话摘要】")).count();
        assertEquals(1, summaryCount);
    }

    private static List<ChatMessage> longConversation() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("system"));
        for (int i = 0; i < 8; i++) {
            messages.add(UserMessage.from("第 " + i + " 轮消息，手机号 13812345678"));
            messages.add(AiMessage.from("第 " + i + " 轮回答"));
        }
        return messages;
    }

    private static final class FakeChatModel implements ChatModel {
        private final String output;

        private FakeChatModel(String output) {
            this.output = output;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(output))
                    .build();
        }
    }

}
