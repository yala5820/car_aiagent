package com.hirain.aiagent.rag.store;

import java.io.IOException;
import java.io.InputStream;

/** Asset 输入边界；生产适配 Android AssetManager，单元测试可使用受控内存/文件实现。 */
public interface KnowledgeAssetSource {
    InputStream open(String relativePath) throws IOException;
}
