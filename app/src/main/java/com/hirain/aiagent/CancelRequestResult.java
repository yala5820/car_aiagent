package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class CancelRequestResult implements Parcelable {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    public static final String STATUS_ALREADY_FINISHED = "ALREADY_FINISHED";

    private boolean success;
    private String requestId;
    private String status;
    private String reason;
    private long timestamp;

    public CancelRequestResult() {}

    protected CancelRequestResult(Parcel in) {
        success = in.readByte() != 0;
        requestId = in.readString();
        status = in.readString();
        reason = in.readString();
        timestamp = in.readLong();
    }

    public static final Creator<CancelRequestResult> CREATOR = new Creator<CancelRequestResult>() {
        @Override
        public CancelRequestResult createFromParcel(Parcel in) { return new CancelRequestResult(in); }
        @Override
        public CancelRequestResult[] newArray(int size) { return new CancelRequestResult[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeString(requestId);
        dest.writeString(status);
        dest.writeString(reason);
        dest.writeLong(timestamp);
    }

    // ── 工厂方法 ──

    public static CancelRequestResult accepted(String requestId, String reason, long timestamp) {
        CancelRequestResult result = new CancelRequestResult();
        result.setSuccess(true);
        result.setRequestId(requestId);
        result.setStatus(STATUS_ACCEPTED);
        result.setReason(reason);
        result.setTimestamp(timestamp);
        return result;
    }

    public static CancelRequestResult notFound(String requestId, String reason, long timestamp) {
        CancelRequestResult result = new CancelRequestResult();
        result.setSuccess(false);
        result.setRequestId(requestId);
        result.setStatus(STATUS_NOT_FOUND);
        result.setReason(reason);
        result.setTimestamp(timestamp);
        return result;
    }

    public static CancelRequestResult alreadyFinished(String requestId, String reason, long timestamp) {
        CancelRequestResult result = new CancelRequestResult();
        result.setSuccess(false);
        result.setRequestId(requestId);
        result.setStatus(STATUS_ALREADY_FINISHED);
        result.setReason(reason);
        result.setTimestamp(timestamp);
        return result;
    }

    // ── getter/setter ──

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
