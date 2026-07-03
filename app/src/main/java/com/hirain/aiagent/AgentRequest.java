package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

public class AgentRequest implements Parcelable {

    private String requestId;
    private String sessionId;
    private String sourceApp;
    private String text;
    private String inputType;
    private String sceneType;
    private String imagePath;
    private Map<String, String> extraContext;
    private long timestamp;

    public AgentRequest() {}

    protected AgentRequest(Parcel in) {
        requestId = in.readString();
        sessionId = in.readString();
        sourceApp = in.readString();
        text = in.readString();
        inputType = in.readString();
        sceneType = in.readString();
        imagePath = in.readString();
        timestamp = in.readLong();
        int mapSize = in.readInt();
        if (mapSize > 0) {
            extraContext = new HashMap<>(mapSize);
            for (int i = 0; i < mapSize; i++) {
                extraContext.put(in.readString(), in.readString());
            }
        }
    }

    public static final Creator<AgentRequest> CREATOR = new Creator<AgentRequest>() {
        @Override
        public AgentRequest createFromParcel(Parcel in) {
            return new AgentRequest(in);
        }

        @Override
        public AgentRequest[] newArray(int size) {
            return new AgentRequest[size];
        }
    };

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(sessionId);
        dest.writeString(sourceApp);
        dest.writeString(text);
        dest.writeString(inputType);
        dest.writeString(sceneType);
        dest.writeString(imagePath);
        dest.writeLong(timestamp);
        if (extraContext != null && !extraContext.isEmpty()) {
            dest.writeInt(extraContext.size());
            for (Map.Entry<String, String> entry : extraContext.entrySet()) {
                dest.writeString(entry.getKey());
                dest.writeString(entry.getValue());
            }
        } else {
            dest.writeInt(0);
        }
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getSourceApp() { return sourceApp; }
    public void setSourceApp(String v) { this.sourceApp = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getInputType() { return inputType; }
    public void setInputType(String v) { this.inputType = v; }
    public String getSceneType() { return sceneType; }
    public void setSceneType(String v) { this.sceneType = v; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String v) { this.imagePath = v; }
    public Map<String, String> getExtraContext() { return extraContext; }
    public void setExtraContext(Map<String, String> v) { this.extraContext = v; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
}
