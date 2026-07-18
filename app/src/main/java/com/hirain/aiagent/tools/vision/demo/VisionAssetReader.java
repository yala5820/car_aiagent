package com.hirain.aiagent.tools.vision.demo;

import java.io.IOException;

/** assets 读取边界，方便纯 JVM 测试替换为内存实现。 */
public interface VisionAssetReader {
    byte[] read(String assetPath) throws IOException;
}
