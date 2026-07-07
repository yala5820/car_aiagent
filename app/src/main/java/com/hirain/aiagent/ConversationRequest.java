package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class ConversationRequest implements Parcelable {

    private String userId;
    private String sessionId;
    private String personaId;
    private String title;
    private String sourceApp;
    private long timestamp;

    public ConversationRequest() {}

    protected ConversationRequest(Parcel in) {
        userId = in.readString();
        sessionId = in.readString();
        personaId = in.readString();
        title = in.readString();
        sourceApp = in.readString();
        timestamp = in.readLong();
    }

    public static final Creator<ConversationRequest> CREATOR = new Creator<ConversationRequest>() {
        @Override
        public ConversationRequest createFromParcel(Parcel in) { return new ConversationRequest(in); }
        @Override
        public ConversationRequest[] newArray(int size) { return new ConversationRequest[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(userId);
        dest.writeString(sessionId);
        dest.writeString(personaId);
        dest.writeString(title);
        dest.writeString(sourceApp);
        dest.writeLong(timestamp);
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceApp() { return sourceApp; }
    public void setSourceApp(String sourceApp) { this.sourceApp = sourceApp; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
