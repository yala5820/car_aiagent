package com.hirain.aiagent.rag.config;

/** 与离线 Bundle/Golden 共同冻结的协议版本，修改必须先回到共享规范源。 */
public final class RagSchemaConstants {
    public static final int MANIFEST_VERSION = 2;
    public static final int SOURCE_LOCATOR_VERSION = 1;
    public static final String LEXICAL_ANALYZER_VERSION = "lexical-analyzer-v2-fielded";
    public static final int EMBEDDING_TEMPLATE_VERSION = 2;
    private RagSchemaConstants() { }
}
