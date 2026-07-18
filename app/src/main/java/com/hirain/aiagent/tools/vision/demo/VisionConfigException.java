package com.hirain.aiagent.tools.vision.demo;

/** 面向 Tool 的稳定 Demo 配置/图片读取失败。 */
public final class VisionConfigException extends Exception {
    private final String status;
    public VisionConfigException(String status, String message) { super(message); this.status = status; }
    public String status() { return status; }
}
