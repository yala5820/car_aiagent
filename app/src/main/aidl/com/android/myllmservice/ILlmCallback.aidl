package com.android.myllmservice;

import com.android.myllmservice.LlmChoice;

interface ILlmCallback {
    oneway void onLlmResponse(in int usr_id, in String id, in int time_stamp, in String model_name, in List<LlmChoice> choices);
    oneway void onError(in int usr_id, int errorCode, in String message);
}
