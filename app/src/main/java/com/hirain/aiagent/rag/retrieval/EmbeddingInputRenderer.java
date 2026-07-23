package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagSchemaConstants;
import java.text.Normalizer;

/** 文档 Embedding V1 模板的端侧镜像，仅用于验证共享契约，不得替代离线建库输入。 */
public final class EmbeddingInputRenderer {
    public static final int TEMPLATE_VERSION = RagSchemaConstants.EMBEDDING_TEMPLATE_VERSION;
    public String render(String title, String headingPath, String chunkType, String content) {
        return "文档：" + canonicalize(title) + "\n位置：" + canonicalize(headingPath) + "\n类型：" + canonicalize(chunkType) + "\n内容：" + canonicalize(content);
    }
    private static String canonicalize(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " "); }
}
