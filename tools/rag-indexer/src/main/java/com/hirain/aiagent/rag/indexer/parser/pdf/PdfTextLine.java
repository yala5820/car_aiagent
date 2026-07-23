package com.hirain.aiagent.rag.indexer.parser.pdf;

import com.hirain.aiagent.rag.indexer.model.BoundingBox;

/** 一行按坐标排序后恢复的文本及几何信息。 */
record PdfTextLine(String text, BoundingBox boundingBox, float averageFontSize) {
}
