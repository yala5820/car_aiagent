package com.hirain.aiagent.rag.indexer.parser;

import java.util.List;

/** 统一解析质量验证结果；阻断原因只保存稳定码，不写入正文或底层异常。 */
public record ParseQualityReport(List<String> failures) {
    public ParseQualityReport {
        failures = List.copyOf(failures);
    }

    public boolean isAccepted() {
        return failures.isEmpty();
    }
}
