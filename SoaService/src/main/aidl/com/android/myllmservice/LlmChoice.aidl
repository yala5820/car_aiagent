package com.android.myllmservice;

import com.android.myllmservice.ToolCall;

parcelable LlmChoice {
    String type;
    String finish_reason;
    String reasoning_content;
    String natural_language;
    List<ToolCall> tools;
}