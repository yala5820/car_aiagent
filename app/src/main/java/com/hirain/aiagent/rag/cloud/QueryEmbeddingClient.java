package com.hirain.aiagent.rag.cloud;

import com.hirain.aiagent.runtime.RequestDeadline;

/** 查询 Embedding 只接受已规范化 Query，禁止透传 Profile 或任何车辆运行状态。 */
public interface QueryEmbeddingClient { float[] embed(String requestId, String normalizedQuery, RequestDeadline deadline) throws RagCloudException; }
