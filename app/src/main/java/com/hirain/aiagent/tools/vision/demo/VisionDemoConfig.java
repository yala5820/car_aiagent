package com.hirain.aiagent.tools.vision.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Demo 图片白名单的不可变配置。 */
public final class VisionDemoConfig {
    public static final class ImageEntry {
        public String imageId;
        public String assetPath;
        public String mimeType;
    }
    public int schemaVersion;
    public String defaultImageId;
    public long maxImageBytes;
    public List<ImageEntry> images;

    public ImageEntry find(String imageId) {
        if (images == null || imageId == null) return null;
        for (ImageEntry entry : images) if (imageId.equals(entry.imageId)) return entry;
        return null;
    }
    public boolean isReady() { return images != null && !images.isEmpty() && defaultImageId != null && !defaultImageId.isEmpty(); }
    public List<ImageEntry> imageEntries() { return images == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(images)); }
}
