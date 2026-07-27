package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.config.RagSchemaConstants;
import com.hirain.aiagent.rag.store.HeadingTitleResolver;
import java.text.Normalizer;

/** 文档 Embedding V2 模板的端侧镜像，仅用于验证共享契约，不得替代离线建库输入。 */
public final class EmbeddingInputRenderer {
    public static final int TEMPLATE_VERSION = RagSchemaConstants.EMBEDDING_TEMPLATE_VERSION;
    public String render(String title, String headingPath, String chunkType, String content) {
        return "二级标题：" + canonicalize(HeadingTitleResolver.parentTitle(headingPath, title))
                + "\n内容：" + canonicalize(content);
    }
    private static String canonicalize(String value) { return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " "); }
}
