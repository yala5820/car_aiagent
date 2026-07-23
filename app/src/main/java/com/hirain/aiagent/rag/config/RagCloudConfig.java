package com.hirain.aiagent.rag.config;

/** 云侧请求的集中配置；密钥不属于此对象，始终从受控本地配置注入。 */
public record RagCloudConfig(String region, String workspace, String baseUrl, String embeddingModel, String rerankModel, long timeoutMs, int maxRetries, int maxInputCharacters) {
    public static RagCloudConfig defaults() { return new RagCloudConfig("cn-beijing", "", "", "text-embedding-v4", "", 60_000L, 2, 8_000); }
}
