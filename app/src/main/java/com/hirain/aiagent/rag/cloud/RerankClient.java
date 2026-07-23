package com.hirain.aiagent.rag.cloud;

import com.hirain.aiagent.runtime.RequestDeadline;
import java.util.List;

/** Rerank 失败由上层降级至 RRF；客户端不自行伪造排序结果。 */
public interface RerankClient { List<RerankItem> rerank(String requestId, String normalizedQuery, List<RerankCandidate> candidates, RequestDeadline deadline) throws RagCloudException; }
