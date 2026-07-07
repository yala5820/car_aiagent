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
    private String userId;
    private String personaId;
    private String status;
    private String errorDetail;
    private String clientMessageId;

    public AgentResponse() {}

    protected AgentResponse(Parcel in) {
        requestId = in.readString();
        sessionId = in.readString();
        success = in.readByte() != 0;
        text = in.readString();
        errorType = in.readString();
        timestamp = in.readLong();
        userId = in.readString();
        personaId = in.readString();
        status = in.readString();
        errorDetail = in.readString();
        clientMessageId = in.readString();
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
        dest.writeString(userId);
        dest.writeString(personaId);
        dest.writeString(status);
        dest.writeString(errorDetail);
        dest.writeString(clientMessageId);
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

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
}
