package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import java.util.List;

/** Dense 候选接口：调用方只能得到完成 Metadata 过滤后的 Child 与原始距离。 */
public interface DenseSearcher { List<DenseCandidate> search(KnowledgeStoreGateway gateway, float[] queryEmbedding, VehicleProfile profile, int limit); }
