package com.hirain.aiagent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.trace.AgentTraceRecorder;
import com.hirain.aiagent.trace.TestTraceSupport;
import com.hirain.aiagent.trace.TraceAttributeKeys;
import com.hirain.aiagent.trace.TraceSpanNames;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.Test;

import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

public class MemoryExtractorTraceTest {

    @Test
    public void recordsMemoryExtractSpanWhenExtractionSucceeds() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatModel(
                "[{\"category\":\"preference\",\"key\":\"temperature\",\"value\":\"24 度\",\"confidence\":0.9}]"));

        List<MemoryCandidate> candidates = extractor.extract(
                "我手机号 13812345678，喜欢 24 度",
                "已记住",
                recorder);
        session.close();

        assertEquals(1, candidates.size());
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_EXTRACT, data.getName());
        assertEquals("extract", data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OPERATION)));
        assertEquals(1L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_CANDIDATE_COUNT)).longValue());
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OUTPUT))
                .contains("temperature"));
        assertFalse(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_PROMPT))
                .contains("13812345678"));
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_PROMPT))
                .contains("138****5678"));
    }

    @Test
    public void marksMemoryExtractSpanErrorWhenModelThrows() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryExtractor extractor = new MemoryExtractor(new ThrowingChatModel());

        List<MemoryCandidate> candidates = extractor.extract("我喜欢安静模式", "好的", recorder);
        session.close();

        assertTrue(candidates.isEmpty());
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_EXTRACT, data.getName());
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
        assertEquals("java.lang.IllegalStateException",
                data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.ERROR_TYPE)));
    }

    @Test
    public void recordsModelOutputWhenExtractionJsonCannotBeParsed() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        AgentTraceRecorder recorder = new AgentTraceRecorder(session.traceSession);
        MemoryExtractor extractor = new MemoryExtractor(new FakeChatModel("不是 JSON"));

        List<MemoryCandidate> candidates = extractor.extract("我喜欢安静模式", "好的", recorder);
        session.close();

        assertTrue(candidates.isEmpty());
        SpanData data = session.exporter.spans.get(0);
        assertEquals(TraceSpanNames.MEMORY_EXTRACT, data.getName());
        assertEquals(0L, data.getAttributes().get(AttributeKey.longKey(TraceAttributeKeys.MEMORY_CANDIDATE_COUNT)).longValue());
        assertTrue(data.getAttributes().get(AttributeKey.stringKey(TraceAttributeKeys.MEMORY_OUTPUT))
                .contains("不是 JSON"));
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

    private static final class ThrowingChatModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new IllegalStateException("extract failed");
        }
    }

}
