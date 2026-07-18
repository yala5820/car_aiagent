package com.hirain.aiagent.tools.vision.demo;

/** 已通过白名单和大小检查的 Demo 图片。 */
public final class FrontViewImage {
    private final String imageId, source, mimeType;
    private final byte[] bytes;
    private final long loadedAtMs;
    public FrontViewImage(String imageId, String source, String mimeType, byte[] bytes, long loadedAtMs) {
        this.imageId=imageId; this.source=source; this.mimeType=mimeType; this.bytes=bytes.clone(); this.loadedAtMs=loadedAtMs;
    }
    public String imageId(){return imageId;} public String source(){return source;} public String mimeType(){return mimeType;}
    public byte[] bytes(){return bytes.clone();} public long sizeBytes(){return bytes.length;} public long loadedAtMs(){return loadedAtMs;}
}
