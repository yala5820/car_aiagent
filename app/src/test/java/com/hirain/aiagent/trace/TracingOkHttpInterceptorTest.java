package com.hirain.aiagent.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.Test;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Timeout;

public class TracingOkHttpInterceptorTest {

    @Test
    public void recordsStandardHttpAttributesForSuccessfulResponse() throws Exception {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        Span httpSpan = session.traceSession.startChildSpan("http.client");
        Response response;

        try (Scope ignored = httpSpan.makeCurrent()) {
            response = new TracingOkHttpInterceptor().intercept(
                    new FakeChain(postRequest(), response(200, "OK"), null));
        } finally {
            httpSpan.end();
            session.close();
        }

        SpanData data = findSpan(session.exporter.spans, "http.client");
        assertEquals(200L, data.getAttributes()
                .get(AttributeKey.longKey(TraceAttributeKeys.HTTP_RESPONSE_STATUS_CODE)).longValue());
        assertEquals("POST", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.HTTP_REQUEST_METHOD)));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/chat", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.URL_FULL)));
        assertEquals("dashscope.aliyuncs.com", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.SERVER_ADDRESS)));
        assertTrue(data.getAttributes()
                .get(AttributeKey.longKey(TraceAttributeKeys.HTTP_DURATION_MS)) >= 0);
        assertNull(data.getAttributes().get(AttributeKey.longKey("http.status_code")));
        assertNull(data.getAttributes().get(AttributeKey.stringKey("http.url")));
        assertNull(data.getAttributes().get(AttributeKey.stringKey("http.method")));
    }

    @Test
    public void marksSpanErrorForIOException() {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        Span httpSpan = session.traceSession.startChildSpan("http.client");

        try (Scope ignored = httpSpan.makeCurrent()) {
            new TracingOkHttpInterceptor().intercept(
                    new FakeChain(postRequest(), null, new IOException("network down")));
            fail("IOException should be propagated");
        } catch (IOException e) {
            assertEquals("network down", e.getMessage());
        } finally {
            httpSpan.end();
            session.close();
        }

        SpanData data = findSpan(session.exporter.spans, "http.client");
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
        assertEquals("java.io.IOException", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.ERROR_TYPE)));
        assertEquals("network down", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.ERROR_MESSAGE)));
        assertEquals("POST", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.HTTP_REQUEST_METHOD)));
        assertEquals("https://dashscope.aliyuncs.com/api/v1/chat", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.URL_FULL)));
        assertEquals("dashscope.aliyuncs.com", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.SERVER_ADDRESS)));
        assertTrue(data.getAttributes()
                .get(AttributeKey.longKey(TraceAttributeKeys.HTTP_DURATION_MS)) >= 0);
        assertTrue(hasExceptionEvent(data));
    }

    @Test
    public void marksSpanErrorForHttpFailureStatus() throws Exception {
        TestTraceSupport.TestSession session = TestTraceSupport.redactedSession();
        Span httpSpan = session.traceSession.startChildSpan("http.client");
        Response response;

        try (Scope ignored = httpSpan.makeCurrent()) {
            response = new TracingOkHttpInterceptor().intercept(
                    new FakeChain(postRequest(), response(503, "Service Unavailable"), null));
        } finally {
            httpSpan.end();
            session.close();
        }

        SpanData data = findSpan(session.exporter.spans, "http.client");
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
        assertEquals("503", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.ERROR_TYPE)));
        assertEquals(503L, data.getAttributes()
                .get(AttributeKey.longKey(TraceAttributeKeys.HTTP_RESPONSE_STATUS_CODE)).longValue());
        assertEquals("POST", data.getAttributes()
                .get(AttributeKey.stringKey(TraceAttributeKeys.HTTP_REQUEST_METHOD)));
    }

    private static Request postRequest() {
        return new Request.Builder()
                .url("https://dashscope.aliyuncs.com/api/v1/chat")
                .post(RequestBody.create(null, "{}"))
                .build();
    }

    private static Response response(int code, String message) {
        return new Response.Builder()
                .request(postRequest())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .build();
    }

    private static SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) return span;
        }
        throw new AssertionError("Missing span: " + name);
    }

    private static boolean hasExceptionEvent(SpanData span) {
        for (EventData event : span.getEvents()) {
            if ("exception".equals(event.getName())) return true;
        }
        return false;
    }

    private static final class FakeChain implements Interceptor.Chain {
        private final Request request;
        private final Response response;
        private final IOException exception;

        private FakeChain(Request request, Response response, IOException exception) {
            this.request = request;
            this.response = response;
            this.exception = exception;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response proceed(Request request) throws IOException {
            if (exception != null) throw exception;
            return response;
        }

        @Override
        public Connection connection() {
            return null;
        }

        @Override
        public Call call() {
            return new FakeCall(request);
        }

        @Override
        public int connectTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int readTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int writeTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
            return this;
        }
    }

    private static final class FakeCall implements Call {
        private final Request request;

        private FakeCall(Request request) {
            this.request = request;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response execute() throws IOException {
            throw new IOException("not implemented");
        }

        @Override
        public void enqueue(okhttp3.Callback responseCallback) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isExecuted() {
            return false;
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public Call clone() {
            return new FakeCall(request);
        }
    }
}
