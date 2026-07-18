package com.hirain.aiagent.tools.vision;

import static org.junit.Assert.assertTrue;

import com.hirain.aiagent.runtime.RequestDeadline;
import com.hirain.aiagent.runtime.RequestExecutionContext;
import com.hirain.aiagent.tools.vision.demo.FrontViewImage;
import com.hirain.aiagent.tools.vision.demo.FrontViewImageProvider;
import com.hirain.aiagent.tools.vision.demo.VisionConfigException;
import com.hirain.aiagent.tools.vision.model.VisionAnalysis;
import com.hirain.aiagent.tools.vision.model.VisionAnalyzer;
import com.hirain.aiagent.trace.VisionTraceRecorder;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.Test;

import java.util.List;

public class FrontViewVisionToolTest {
    @Test public void missingExecutionContextFailsClosed() {
        FrontViewVisionTool tool = tool((id) -> { throw new VisionConfigException("CONFIG_NOT_READY", "empty"); });
        assertTrue(tool.frontCameraInteraction("模型参数").contains("EXECUTION_CONTEXT_MISSING"));
    }
    @Test public void providerFailureIsStableJson() {
        FrontViewVisionTool tool = tool((id) -> { throw new VisionConfigException("CONFIG_NOT_READY", "empty"); });
        try (RequestExecutionContext.Scope ignored = RequestExecutionContext.bind("req", RequestDeadline.standard(System.currentTimeMillis()), "前方是什么", null)) {
            assertTrue(tool.frontCameraInteraction("无关参数").contains("CONFIG_NOT_READY"));
        }
    }
    @Test public void successfulAnalysisProducesNoImageBytes() {
        FrontViewVisionTool tool = new FrontViewVisionTool(id -> new FrontViewImage("x", "DEMO_ASSET", "image/jpeg", new byte[]{(byte)255,(byte)216,1}, 1L),
                (question, image) -> { VisionAnalysis a = new VisionAnalysis(); a.summary="一辆车"; a.observations=List.of("车辆"); a.uncertainties=List.of(); return a; },
                new VisionTraceRecorder(OpenTelemetry.noop().getTracer("test")));
        try (RequestExecutionContext.Scope ignored = RequestExecutionContext.bind("req", RequestDeadline.standard(System.currentTimeMillis()), "前方是什么", null)) {
            String result = tool.frontCameraInteraction("模型篡改的问题");
            assertTrue(result.contains("SUCCESS")); assertTrue(!result.contains("/9j"));
        }
    }
    private static FrontViewVisionTool tool(FrontViewImageProvider provider) {
        VisionAnalyzer analyzer = (q, image) -> { throw new AssertionError("must not call analyzer"); };
        return new FrontViewVisionTool(provider, analyzer, new VisionTraceRecorder(OpenTelemetry.noop().getTracer("test")));
    }
}
