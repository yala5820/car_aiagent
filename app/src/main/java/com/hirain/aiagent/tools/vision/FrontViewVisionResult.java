package com.hirain.aiagent.tools.vision;

import com.hirain.aiagent.tools.vision.demo.FrontViewImage;
import com.hirain.aiagent.tools.vision.model.VisionAnalysis;
import java.util.List;

/** Tool 与 Loop 间稳定 JSON 协议。 */
public final class FrontViewVisionResult {
    public int schemaVersion=1; public boolean success; public String status; public String reason;
    public String source,imageId,mimeType,model; public long sizeBytes,loadedAtMs; public VisionAnalysis analysis; public List<String> limitations;
    public static FrontViewVisionResult success(FrontViewImage image,VisionAnalysis analysis){FrontViewVisionResult r=new FrontViewVisionResult();r.success=true;r.status="SUCCESS";r.reason="ok";r.source=image.source();r.imageId=image.imageId();r.mimeType=image.mimeType();r.sizeBytes=image.sizeBytes();r.loadedAtMs=image.loadedAtMs();r.model="qwen-vl-max";r.analysis=analysis;r.limitations=List.of("该结果基于预置测试图片，不代表实时道路画面");return r;}
    public static FrontViewVisionResult failure(String status,String reason){FrontViewVisionResult r=new FrontViewVisionResult();r.success=false;r.status=status;r.reason=reason;return r;}
}
