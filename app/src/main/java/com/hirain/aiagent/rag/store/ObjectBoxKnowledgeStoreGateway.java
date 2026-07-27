package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity_;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity_;
import io.objectbox.BoxStore;
import java.util.List;

/** ObjectBox 的只读业务适配层；不暴露 BoxStore，写 API 不会越过 Store Manager。 */
public final class ObjectBoxKnowledgeStoreGateway implements KnowledgeStoreGateway {
 private final BoxStore store;
 public ObjectBoxKnowledgeStoreGateway(BoxStore store){this.store=store;}
 @Override public KnowledgeStoreMetadataEntity metadata(){var values=store.boxFor(KnowledgeStoreMetadataEntity.class).getAll();if(values.size()!=1)throw new IllegalStateException("Store Metadata 数量非法");return values.get(0);}
 @Override public List<KnowledgeChunkEntity> childChunksByIds(List<String> chunkIds){if(chunkIds==null||chunkIds.isEmpty())return List.of();return store.boxFor(KnowledgeChunkEntity.class).query(KnowledgeChunkEntity_.chunkId.oneOf(chunkIds.toArray(new String[0]))).build().find();}
 @Override public List<KnowledgeChunkEntity> parentChunksByIds(List<String> parentIds){if(parentIds==null||parentIds.isEmpty())return List.of();return store.boxFor(KnowledgeChunkEntity.class).query(KnowledgeChunkEntity_.chunkId.oneOf(parentIds.toArray(new String[0])).and(KnowledgeChunkEntity_.chunkLevel.equal("PARENT"))).build().find();}
 @Override public List<KnowledgeChunkEntity> childChunksByEntityIds(List<Long> entityIds){if(entityIds==null||entityIds.isEmpty())return List.of();long[] ids=new long[entityIds.size()];for(int index=0;index<ids.length;index++)ids[index]=entityIds.get(index);return store.boxFor(KnowledgeChunkEntity.class).get(ids);}
 @Override public List<KnowledgeChunkEntity> nearestChildren(float[] queryVector,int limit){if(queryVector==null||queryVector.length!=1024||limit<1)return List.of();return store.boxFor(KnowledgeChunkEntity.class).query(KnowledgeChunkEntity_.embedding.nearestNeighbors(queryVector,limit)).build().find();}
 @Override public List<LexicalTermEntity> lexicalTermsByTerms(List<String> terms){if(terms==null||terms.isEmpty())return List.of();return store.boxFor(LexicalTermEntity.class).query(LexicalTermEntity_.term.oneOf(terms.toArray(new String[0]))).build().find();}
 @Override public void close(){store.close();}
}
