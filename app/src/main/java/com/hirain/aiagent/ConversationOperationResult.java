package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class ConversationOperationResult implements Parcelable {

    private boolean success;
    private String operation;
    private String errorType;
    private String errorDetail;
    private ConversationInfo conversationInfo;

    public ConversationOperationResult() {}

    protected ConversationOperationResult(Parcel in) {
        success = in.readByte() != 0;
        operation = in.readString();
        errorType = in.readString();
        errorDetail = in.readString();
        conversationInfo = in.readParcelable(ConversationInfo.class.getClassLoader());
    }

    public static final Creator<ConversationOperationResult> CREATOR = new Creator<ConversationOperationResult>() {
        @Override
        public ConversationOperationResult createFromParcel(Parcel in) { return new ConversationOperationResult(in); }
        @Override
        public ConversationOperationResult[] newArray(int size) { return new ConversationOperationResult[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeString(operation);
        dest.writeString(errorType);
        dest.writeString(errorDetail);
        dest.writeParcelable(conversationInfo, flags);
    }

    // ── 工厂方法 ──

    public static ConversationOperationResult success(String operation, ConversationInfo info) {
        ConversationOperationResult result = new ConversationOperationResult();
        result.setSuccess(true);
        result.setOperation(operation);
        result.setConversationInfo(info);
        return result;
    }

    public static ConversationOperationResult failure(String operation, String errorType, String errorDetail) {
        ConversationOperationResult result = new ConversationOperationResult();
        result.setSuccess(false);
        result.setOperation(operation);
        result.setErrorType(errorType);
        result.setErrorDetail(errorDetail);
        return result;
    }

    // ── getter/setter ──

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public ConversationInfo getConversationInfo() { return conversationInfo; }
    public void setConversationInfo(ConversationInfo conversationInfo) { this.conversationInfo = conversationInfo; }
}
