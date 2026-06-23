package com.hirain.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public class AIAgentData implements Parcelable {
    private byte[] value;

    public AIAgentData() {}

    protected AIAgentData(Parcel source) {
        value = source.createByteArray();
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(value);
    }

    public void readFromParcel(Parcel source) {
        value = source.createByteArray();
    }

    @Override
    public String toString() {
        return "AIAgentData size = " + (value != null ? value.length : 0);
    }

    public static final Parcelable.Creator<AIAgentData> CREATOR =
            new Parcelable.Creator<AIAgentData>() {
                @Override
                public AIAgentData createFromParcel(Parcel source) {
                    return new AIAgentData(source);
                }

                @Override
                public AIAgentData[] newArray(int size) {
                    return new AIAgentData[size];
                }
            };
}
