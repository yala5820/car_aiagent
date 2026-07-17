package com.hirain.aiagent.eval;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 缓存 APK 与 Prompt 指纹，避免 Binder 请求重复扫描 assets。 */
public final class EvalVersionFingerprintProvider {
    private final Context context;
    private volatile Map<String, Object> cached;
    public EvalVersionFingerprintProvider(Context context) { this.context = context.getApplicationContext(); }
    public Map<String, Object> get() {
        Map<String, Object> current = cached;
        if (current != null) return current;
        synchronized (this) {
            if (cached == null) cached = create();
            return cached;
        }
    }
    private Map<String, Object> create() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageName", context.getPackageName());
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            result.put("versionName", info.versionName); result.put("versionCode", info.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException ignored) { result.put("versionName", null); result.put("versionCode", null); }
        result.put("stateSchemaVersion", "1.0");
        result.put("promptHash", promptHash());
        result.put("sourceRevision", null);
        return result;
    }
    private String promptHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] files = context.getAssets().list("prompts");
            if (files == null) return null;
            Arrays.sort(files);
            for (String file : files) updateAsset(digest, "prompts/" + file);
            byte[] bytes = digest.digest(); StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ignored) { return null; }
    }
    private void updateAsset(MessageDigest digest, String path) throws Exception {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) { Arrays.sort(children); for (String child : children) updateAsset(digest, path + "/" + child); return; }
        byte[] pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8); digest.update(java.nio.ByteBuffer.allocate(4).putInt(pathBytes.length).array()); digest.update(pathBytes);
        try (InputStream input = context.getAssets().open(path)) { byte[] bytes = input.readAllBytes(); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
    }
}
