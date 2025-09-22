package com.android.myllmservice;

import com.android.myllmservice.LlmMessage;
import com.android.myllmservice.ILlmCallback;

interface ILlmService {
    int asyncInvoke(in List<LlmMessage> messages, in String url, in String api_key, in String model_name, in ILlmCallback llm_callback, int usr_id);
    int asyncStream(in List<LlmMessage> messages, in String url, in String api_key, in String model_name, in ILlmCallback llm_callback, int usr_id);
}