package com.hirain.aiagent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.langchain4j.data.message.UserMessage;

/**
 * Context prepare 结果 — 包含 ContextFrame、currentUserMessage 和 Provider 诊断。
 * <p>
 * status 取值为 SUCCESS / FAILED / CANCELLED。
 */
public final class ContextPrepareResult {

    public enum Status { SUCCESS, FAILED, CANCELLED }

    private final Status status;
    private final ContextFrame frame;
    private final UserMessage currentUserMessage;
    private final ContextCancelChecker cancelChecker;
    private final List<ContextProviderOutcome> providerOutcomes;
    private final ContextErrorCode errorCode;
    private final String errorDetail;

    private ContextPrepareResult(Status status, ContextFrame frame,
                                  UserMessage currentUserMessage,
                                  ContextCancelChecker cancelChecker,
                                  List<ContextProviderOutcome> providerOutcomes,
                                  ContextErrorCode errorCode,
                                  String errorDetail) {
        this.status = status;
        this.frame = frame;
        this.currentUserMessage = currentUserMessage;
        this.cancelChecker = cancelChecker;
        this.providerOutcomes = providerOutcomes != null
                ? Collections.unmodifiableList(new ArrayList<>(providerOutcomes))
                : List.of();
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
    }

    public static ContextPrepareResult success(ContextFrame frame,
                                                 UserMessage currentUserMessage,
                                                 ContextCancelChecker cancelChecker,
                                                 List<ContextProviderOutcome> providerOutcomes) {
        return new ContextPrepareResult(Status.SUCCESS, frame, currentUserMessage,
                cancelChecker, providerOutcomes, null, null);
    }

    public static ContextPrepareResult failed(ContextErrorCode errorCode,
                                                String errorDetail) {
        return new ContextPrepareResult(Status.FAILED, null, null,
                ContextCancelChecker.neverCancelled(), List.of(), errorCode, errorDetail);
    }

    public static ContextPrepareResult cancelled(String detail) {
        return new ContextPrepareResult(Status.CANCELLED, null, null,
                ContextCancelChecker.neverCancelled(), List.of(),
                ContextErrorCode.CONTEXT_CANCELLED, detail);
    }

    public Status status() { return status; }
    public ContextFrame frame() { return frame; }
    public UserMessage currentUserMessage() { return currentUserMessage; }
    public ContextCancelChecker cancelChecker() { return cancelChecker; }
    public List<ContextProviderOutcome> providerOutcomes() { return providerOutcomes; }
    public ContextErrorCode errorCode() { return errorCode; }
    public String errorDetail() { return errorDetail; }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean isCancelled() { return status == Status.CANCELLED; }
}
