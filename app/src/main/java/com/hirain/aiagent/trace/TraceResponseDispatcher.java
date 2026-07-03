package com.hirain.aiagent.trace;

import com.hirain.aiagent.AgentResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.trace.Span;

public class TraceResponseDispatcher {

    private final TraceSession session;
    private final AtomicBoolean responseSent = new AtomicBoolean(false);

    public TraceResponseDispatcher(TraceSession session) {
        this.session = session;
    }

    public boolean dispatch(AgentResponse response, String statusMessage, ResponseSender sender) {
        if (!responseSent.compareAndSet(false, true)) {
            return false;
        }
        if (session != null) {
            session.setStatus(response != null && response.isSuccess(),
                    statusMessageFor(response, statusMessage));
            recordResponseDispatch(response);
        }
        if (sender != null && response != null) {
            sender.send(response);
        }
        return true;
    }

    public boolean dispatchAndClose(AgentResponse response, String statusMessage, ResponseSender sender) {
        boolean dispatched = dispatch(response, statusMessage, sender);
        if (dispatched && session != null) {
            session.close();
        }
        return dispatched;
    }

    private void recordResponseDispatch(AgentResponse response) {
        if (response == null) return;
        long textLength = response.getText() != null ? response.getText().length() : 0L;
        session.setAttribute(TraceAttributeKeys.RESPONSE_SUCCESS, response.isSuccess());
        session.setAttribute(TraceAttributeKeys.RESPONSE_TEXT_LENGTH, textLength);
        session.setAttribute(TraceAttributeKeys.RESPONSE_ERROR_TYPE, response.getErrorType());

        Span span = session.startChildSpan(TraceSpanNames.RESPONSE_DISPATCH);
        try {
            session.writer().putBoolean(span, TraceAttributeKeys.RESPONSE_SUCCESS, response.isSuccess());
            session.writer().putLong(span, TraceAttributeKeys.RESPONSE_TEXT_LENGTH, textLength);
            session.writer().putString(span, TraceAttributeKeys.RESPONSE_ERROR_TYPE, response.getErrorType());
        } finally {
            span.end();
        }
    }

    private String statusMessageFor(AgentResponse response, String statusMessage) {
        if (statusMessage != null) return statusMessage;
        if (response != null && response.getErrorType() != null) return response.getErrorType();
        return "Unknown error";
    }

    @FunctionalInterface
    public interface ResponseSender {
        void send(AgentResponse response);
    }
}
