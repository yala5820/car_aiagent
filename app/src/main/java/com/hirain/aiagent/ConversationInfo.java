package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class ConversationInfo implements Parcelable {

    private String userId;
    private String sessionId;
    private String personaId;
    private String title;
    private boolean active;
    private long createdAt;
    private long updatedAt;
    private long endedAt;
    private int messageCount;
    private int tokenEstimate;
    private int compressionCount;

    public ConversationInfo() {}

    protected ConversationInfo(Parcel in) {
        userId = in.readString();
        sessionId = in.readString();
        personaId = in.readString();
        title = in.readString();
        active = in.readByte() != 0;
        createdAt = in.readLong();
        updatedAt = in.readLong();
        endedAt = in.readLong();
        messageCount = in.readInt();
        tokenEstimate = in.readInt();
        compressionCount = in.readInt();
    }

    public static final Creator<ConversationInfo> CREATOR = new Creator<ConversationInfo>() {
        @Override
        public ConversationInfo createFromParcel(Parcel in) { return new ConversationInfo(in); }
        @Override
        public ConversationInfo[] newArray(int size) { return new ConversationInfo[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(userId);
        dest.writeString(sessionId);
        dest.writeString(personaId);
        dest.writeString(title);
        dest.writeByte((byte) (active ? 1 : 0));
        dest.writeLong(createdAt);
        dest.writeLong(updatedAt);
        dest.writeLong(endedAt);
        dest.writeInt(messageCount);
        dest.writeInt(tokenEstimate);
        dest.writeInt(compressionCount);
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public long getEndedAt() { return endedAt; }
    public void setEndedAt(long endedAt) { this.endedAt = endedAt; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public int getTokenEstimate() { return tokenEstimate; }
    public void setTokenEstimate(int tokenEstimate) { this.tokenEstimate = tokenEstimate; }
    public int getCompressionCount() { return compressionCount; }
    public void setCompressionCount(int compressionCount) { this.compressionCount = compressionCount; }
}
