package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import java.util.List;

/** Lexical 召回同样在返回前执行 Child/Metadata 资格过滤。 */
public interface LexicalSearcher { List<RankedCandidate> search(KnowledgeStoreGateway gateway, List<String> queryTerms, VehicleProfile profile, int limit); }
