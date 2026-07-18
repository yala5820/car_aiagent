package com.hirain.aiagent.tools.vision.model;
public final class VisionAnalysisException extends Exception { private final String status; public VisionAnalysisException(String status,String message){super(message);this.status=status;} public String status(){return status;} }
