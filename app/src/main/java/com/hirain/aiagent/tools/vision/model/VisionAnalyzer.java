package com.hirain.aiagent.tools.vision.model;
import com.hirain.aiagent.tools.vision.demo.FrontViewImage;
public interface VisionAnalyzer { VisionAnalysis analyze(String question, FrontViewImage image) throws VisionAnalysisException; }
