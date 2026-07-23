package com.hirain.aiagent.rag.indexer.report;

import java.util.List;

/** 发布资格由硬门禁集中计算；任何 ERROR、缺向量或未批准配置都不能被调用参数覆盖。 */
public final class PublishabilityEvaluator {
    public boolean evaluate(List<String> errorCodes, boolean allChildEmbeddingsPresent, boolean configurationApproved,
                            boolean storeVerified, boolean manifestValidated) {
        return errorCodes.isEmpty() && allChildEmbeddingsPresent && configurationApproved && storeVerified && manifestValidated;
    }
}
