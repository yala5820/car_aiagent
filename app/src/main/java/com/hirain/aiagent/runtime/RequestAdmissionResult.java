package com.hirain.aiagent.runtime;

/** 单槽位请求准入结果。 */
public final class RequestAdmissionResult {

    public enum Status {
        ACCEPTED,
        BUSY,
        DUPLICATE_ACTIVE_REQUEST_ID,
        DUPLICATE_FINISHED_REQUEST_ID
    }

    private final Status status;
    private final ActiveRequest activeRequest;

    private RequestAdmissionResult(Status status, ActiveRequest activeRequest) {
        this.status = status;
        this.activeRequest = activeRequest;
    }

    public static RequestAdmissionResult accepted(ActiveRequest activeRequest) {
        return new RequestAdmissionResult(Status.ACCEPTED, activeRequest);
    }

    public static RequestAdmissionResult rejected(Status status) {
        if (status == null || status == Status.ACCEPTED) {
            throw new IllegalArgumentException("rejected status must not be ACCEPTED");
        }
        return new RequestAdmissionResult(status, null);
    }

    public Status status() { return status; }
    public ActiveRequest activeRequest() { return activeRequest; }
    public boolean isAccepted() { return status == Status.ACCEPTED; }
}
