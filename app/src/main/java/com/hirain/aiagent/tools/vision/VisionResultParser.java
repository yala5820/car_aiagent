package com.hirain.aiagent.tools.vision;
import com.google.gson.Gson;
/** Loop 只按协议校验 Tool 业务成功，不从中文文本推断。 */
public final class VisionResultParser { private VisionResultParser(){} public static FrontViewVisionResult parse(String json){try{FrontViewVisionResult r=new Gson().fromJson(json,FrontViewVisionResult.class);return r!=null&&r.schemaVersion==1&&r.status!=null?r:null;}catch(Exception e){return null;}} }
