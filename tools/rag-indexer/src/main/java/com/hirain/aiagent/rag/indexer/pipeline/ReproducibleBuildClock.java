package com.hirain.aiagent.rag.indexer.pipeline;

import java.time.Instant;

/** SOURCE_DATE_EPOCH 存在时固定构建逻辑时间；未设置时只用于非发布调试输出。 */
public final class ReproducibleBuildClock {
    public Instant now() {
        String epoch = System.getenv("SOURCE_DATE_EPOCH");
        if (epoch == null || epoch.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(epoch));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("SOURCE_DATE_EPOCH_INVALID");
        }
    }
}
