package com.hirain.aiagent.vision.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 视觉策略的不可变判定结果，供 Runtime、Context 与 Trace 共同使用。 */
public final class VisionIntentDecision {
    private final VisionRequirement requirement;
    private final List<String> matchedSignals;
    private final String reason;
    private final boolean compoundIntentDetected;

    public VisionIntentDecision(VisionRequirement requirement, List<String> matchedSignals,
                                String reason, boolean compoundIntentDetected) {
        this.requirement = requirement != null ? requirement : VisionRequirement.NONE;
        this.matchedSignals = Collections.unmodifiableList(new ArrayList<>(
                matchedSignals != null ? matchedSignals : List.of()));
        this.reason = reason != null ? reason : "unspecified";
        this.compoundIntentDetected = compoundIntentDetected;
    }

    public static VisionIntentDecision none(String reason) {
        return new VisionIntentDecision(VisionRequirement.NONE, List.of(), reason, false);
    }

    public VisionRequirement requirement() { return requirement; }
    public List<String> matchedSignals() { return matchedSignals; }
    public String reason() { return reason; }
    public boolean compoundIntentDetected() { return compoundIntentDetected; }
}
