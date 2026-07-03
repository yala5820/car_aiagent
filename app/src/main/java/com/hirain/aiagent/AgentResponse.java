package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class AgentResponse implements Parcelable {

    private String requestId;
    private String sessionId;
    private boolean success;
    private String text;
    private String errorType;
    private long timestamp;

    public AgentResponse() {}

    protected AgentResponse(Parcel in) {
        requestId = in.readString();
        sessionId = in.readString();
        success = in.readByte() != 0;
        text = in.readString();
        errorType = in.readString();
        timestamp = in.readLong();
    }

    public static final Creator<AgentResponse> CREATOR = new Creator<AgentResponse>() {
        @Override
        public AgentResponse createFromParcel(Parcel in) {
            return new AgentResponse(in);
        }

        @Override
        public AgentResponse[] newArray(int size) {
            return new AgentResponse[size];
        }
    };

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(sessionId);
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeString(text);
        dest.writeString(errorType);
        dest.writeLong(timestamp);
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
}
