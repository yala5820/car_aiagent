package com.hirain.aiagent.tools.vision.demo;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Android AssetManager 适配，不负责路径授权；授权由 Provider 的配置白名单完成。 */
public final class AndroidVisionAssetReader implements VisionAssetReader {
    private final Context context;
    public AndroidVisionAssetReader(Context context) { this.context = context.getApplicationContext(); }
    @Override public byte[] read(String assetPath) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
