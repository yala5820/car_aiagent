package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.store.KnowledgeStoreGateway;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;
import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Parent 解析器只消费已选 Child 的 parentChunkId，不会把 Parent 混入初始召回。 */
public class ParentContextResolverTest {
    @Test public void requestsOnlyParentsReferencedBySelectedChildren() { KnowledgeChunkEntity child = new KnowledgeChunkEntity(); child.parentChunkId = "parent-1"; KnowledgeChunkEntity parent = new KnowledgeChunkEntity(); parent.chunkId = "parent-1"; List<KnowledgeChunkEntity> result = new ParentContextResolver().resolve(new Gateway(parent), List.of(child)); assertEquals(1, result.size()); assertEquals("parent-1", result.get(0).chunkId); }
    static final class Gateway implements KnowledgeStoreGateway { final KnowledgeChunkEntity parent; Gateway(KnowledgeChunkEntity parent){this.parent=parent;} public KnowledgeStoreMetadataEntity metadata(){return new KnowledgeStoreMetadataEntity();} public List<KnowledgeChunkEntity> childChunksByIds(List<String> ids){return ids.equals(List.of("parent-1"))?List.of(parent):List.of();} public List<KnowledgeChunkEntity> childChunksByEntityIds(List<Long> ids){return List.of();} public List<KnowledgeChunkEntity> nearestChildren(float[] vector,int limit){return List.of();} public List<LexicalTermEntity> lexicalTermsByTerms(List<String> terms){return List.of();} public void close(){} }
}
