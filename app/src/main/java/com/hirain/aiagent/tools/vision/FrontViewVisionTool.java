package com.hirain.aiagent.tools.vision;

import com.google.gson.Gson;
import com.hirain.aiagent.runtime.RequestExecutionContext;
import com.hirain.aiagent.tools.vision.demo.FrontViewImage;
import com.hirain.aiagent.tools.vision.demo.FrontViewImageProvider;
import com.hirain.aiagent.tools.vision.demo.VisionConfigException;
import com.hirain.aiagent.tools.vision.model.VisionAnalysis;
import com.hirain.aiagent.tools.vision.model.VisionAnalysisException;
import com.hirain.aiagent.tools.vision.model.VisionAnalyzer;
import com.hirain.aiagent.trace.VisionTraceRecorder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** 前向视觉复合 Tool：受控图片 -> VLM -> 无图片的结构化证据。 */
public final class FrontViewVisionTool {
    private final FrontViewImageProvider images; private final VisionAnalyzer analyzer; private final VisionTraceRecorder trace;
    public FrontViewVisionTool(FrontViewImageProvider images,VisionAnalyzer analyzer,VisionTraceRecorder trace){this.images=images;this.analyzer=analyzer;this.trace=trace;}
    @Tool(name="front_camera_interaction",value="前向 Demo 摄像头视觉问答工具。仅用于回答当前前方画面问题。")
    public String frontCameraInteraction(@P("用户关于前方视野的文本提问") String ignoredModelText){
        RequestExecutionContext.State ctx=RequestExecutionContext.current();
        if(ctx==null||ctx.originalUserQuestion()==null||ctx.originalUserQuestion().isBlank()) { trace.recordResult("EXECUTION_CONTEXT_MISSING"); return json(FrontViewVisionResult.failure("EXECUTION_CONTEXT_MISSING","trusted_question_missing")); }
        io.opentelemetry.api.trace.Span load = trace.startImageLoad();
        try {
            FrontViewImage image = images.load(ctx.visionDemoImageId());
            trace.finishImageLoad(load, image.imageId(), image.source(), image.mimeType(),
                    image.sizeBytes(), image.loadedAtMs(), "SUCCESS");
            long start = System.currentTimeMillis();
            io.opentelemetry.api.trace.Span model = trace.startModel();
            try {
                VisionAnalysis analysis = analyzer.analyze(ctx.originalUserQuestion(), image);
                trace.finishModel(model, "SUCCESS", System.currentTimeMillis() - start);
                trace.recordResult("SUCCESS"); return json(FrontViewVisionResult.success(image, analysis));
            } catch (VisionAnalysisException e) {
                trace.finishModel(model, e.status(), System.currentTimeMillis() - start);
                trace.recordResult(e.status()); return json(FrontViewVisionResult.failure(e.status(), e.getMessage()));
            } catch (Exception e) {
                trace.finishModel(model, "INTERNAL_ERROR", System.currentTimeMillis() - start);
                trace.recordResult("INTERNAL_ERROR"); return json(FrontViewVisionResult.failure("INTERNAL_ERROR", "tool_internal_error"));
            }
        } catch (VisionConfigException e) {
            trace.finishImageLoad(load, null, null, null, 0L, 0L, e.status());
            trace.recordResult(e.status()); return json(FrontViewVisionResult.failure(e.status(), e.getMessage()));
        } catch (Exception e) {
            trace.finishImageLoad(load, null, null, null, 0L, 0L, "INTERNAL_ERROR");
            trace.recordResult("INTERNAL_ERROR"); return json(FrontViewVisionResult.failure("INTERNAL_ERROR", "tool_internal_error"));
        }
    }
    private static String json(FrontViewVisionResult r){return new Gson().toJson(r);}
}
