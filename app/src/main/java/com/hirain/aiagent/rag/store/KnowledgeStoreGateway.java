package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import java.util.List;
/** RAG 业务层唯一的只读 Store 查询抽象；后续检索只能依赖此接口而非 ObjectBox Box。 */
public interface KnowledgeStoreGateway extends AutoCloseable {
 KnowledgeStoreMetadataEntity metadata();
 List<KnowledgeChunkEntity> childChunksByIds(List<String> chunkIds);
 List<KnowledgeChunkEntity> childChunksByEntityIds(List<Long> entityIds);
 List<KnowledgeChunkEntity> nearestChildren(float[] queryVector, int limit);
 List<LexicalTermEntity> lexicalTermsByTerms(List<String> terms);
 @Override void close();
}
