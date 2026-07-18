package com.hirain.aiagent.vision.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.intentrouter.IntentConfidence;
import com.hirain.aiagent.intentrouter.IntentResult;
import com.hirain.aiagent.intentrouter.IntentTag;
import org.junit.Test;

import java.util.List;

public class RuleBasedVisionIntentPolicyTest {
    private final RuleBasedVisionIntentPolicy policy = new RuleBasedVisionIntentPolicy();
    @Test public void explicitFrontViewIsRequired() {
        assertEquals(VisionRequirement.REQUIRED, policy.decide("前方是什么？", chat()).requirement());
    }
    @Test public void deviceKnowledgeIsNotVisionRequest() {
        assertEquals(VisionRequirement.NONE, policy.decide("介绍前置摄像头工作原理", chat()).requirement());
    }
    @Test public void compoundRequestFailsClosedToClarification() {
        VisionIntentDecision result = policy.decide("看看前方有什么，然后打开车窗", chat());
        assertTrue(result.compoundIntentDetected());
        assertEquals(VisionRequirement.NONE, result.requirement());
    }
    private static IntentResult chat() { return IntentResult.of(IntentTag.CHAT, IntentConfidence.LOW, List.of(), "", "TEXT", "test"); }
}
