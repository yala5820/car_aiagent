package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

public class ConversationListResponse implements Parcelable {

    private boolean success;
    private String errorType;
    private String errorDetail;
    private String userId;
    private ArrayList<ConversationInfo> conversations;

    public ConversationListResponse() {}

    protected ConversationListResponse(Parcel in) {
        success = in.readByte() != 0;
        errorType = in.readString();
        errorDetail = in.readString();
        userId = in.readString();
        conversations = in.createTypedArrayList(ConversationInfo.CREATOR);
    }

    public static final Creator<ConversationListResponse> CREATOR = new Creator<ConversationListResponse>() {
        @Override
        public ConversationListResponse createFromParcel(Parcel in) { return new ConversationListResponse(in); }
        @Override
        public ConversationListResponse[] newArray(int size) { return new ConversationListResponse[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeString(errorType);
        dest.writeString(errorDetail);
        dest.writeString(userId);
        dest.writeTypedList(conversations);
    }

    // ── 工厂方法 ──

    public static ConversationListResponse success(String userId, ArrayList<ConversationInfo> conversations) {
        ConversationListResponse response = new ConversationListResponse();
        response.setSuccess(true);
        response.setUserId(userId);
        response.setConversations(conversations);
        return response;
    }

    public static ConversationListResponse failure(String userId, String errorType, String errorDetail) {
        ConversationListResponse response = new ConversationListResponse();
        response.setSuccess(false);
        response.setUserId(userId);
        response.setErrorType(errorType);
        response.setErrorDetail(errorDetail);
        response.setConversations(new ArrayList<>());
        return response;
    }

    // ── getter/setter ──

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public ArrayList<ConversationInfo> getConversations() { return conversations; }
    public void setConversations(ArrayList<ConversationInfo> conversations) { this.conversations = conversations; }
}
