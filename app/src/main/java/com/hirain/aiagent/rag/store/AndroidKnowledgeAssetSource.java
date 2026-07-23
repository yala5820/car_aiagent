package com.hirain.aiagent.rag.store;

import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;

/** Android 生产 Asset 适配器；调用方不能通过模型文本改变 Asset 根目录。 */
public final class AndroidKnowledgeAssetSource implements KnowledgeAssetSource {
    private final AssetManager assets;
    public AndroidKnowledgeAssetSource(AssetManager assets) { if (assets == null) throw new IllegalArgumentException("ASSET_MANAGER_MISSING"); this.assets = assets; }
    @Override public InputStream open(String relativePath) throws IOException { return assets.open(relativePath); }
}
