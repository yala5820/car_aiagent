package com.hirain.aiagent.vision.routing;

import com.hirain.aiagent.intentrouter.IntentResult;

/** 不调用模型的前向视觉策略接口。 */
public interface VisionIntentPolicy {
    VisionIntentDecision decide(String userText, IntentResult intentResult);
}
