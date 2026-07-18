package com.hirain.aiagent.tools.vision.demo;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 固定 assets 配置加载器。无图片是合法开发状态，不会阻止 Service 启动。 */
public final class VisionDemoConfigLoader {
    public static final String CONFIG_PATH = "vision/demo/vision-demo-config.json";
    private final VisionAssetReader reader;
    public VisionDemoConfigLoader(VisionAssetReader reader) { this.reader = reader; }
    public VisionDemoConfig load() throws VisionConfigException {
        try { return parse(new String(reader.read(CONFIG_PATH), StandardCharsets.UTF_8)); }
        catch (VisionConfigException e) { throw e; }
        catch (Exception e) { throw new VisionConfigException("CONFIG_INVALID", "config_read_failed"); }
    }
    public static VisionDemoConfig parse(String json) throws VisionConfigException {
        try {
            VisionDemoConfig config = new Gson().fromJson(json, VisionDemoConfig.class);
            if (config == null || config.schemaVersion != 1) throw invalid("schema_version");
            if (config.images == null) config.images = java.util.List.of();
            if (config.maxImageBytes <= 0L) config.maxImageBytes = 5L * 1024L * 1024L;
            Set<String> ids = new HashSet<>();
            for (VisionDemoConfig.ImageEntry entry : config.images) {
                if (entry == null || blank(entry.imageId) || !ids.add(entry.imageId)) throw invalid("image_id");
                if (!safeAssetPath(entry.assetPath)) throw invalid("asset_path");
                if (!("image/jpeg".equals(entry.mimeType) || "image/png".equals(entry.mimeType)
                        || "image/webp".equals(entry.mimeType))) throw invalid("mime_type");
            }
            if (!blank(config.defaultImageId) && config.find(config.defaultImageId) == null) throw invalid("default_image_id");
            return config;
        } catch (VisionConfigException e) { throw e; }
        catch (Exception e) { throw invalid("json"); }
    }
    private static VisionConfigException invalid(String reason) { return new VisionConfigException("CONFIG_INVALID", reason); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean safeAssetPath(String value) {
        return value != null && value.startsWith("vision/demo/images/") && !value.contains("..")
                && !value.contains("\\") && !value.contains("//") && !value.contains(":") && !value.contains("/./");
    }
}
