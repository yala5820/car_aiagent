package com.hirain.aiagent.rag.store;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import com.hirain.aiagent.rag.store.entity.KnowledgeStoreMetadataEntity;import com.hirain.aiagent.rag.store.entity.LexicalTermEntity;
import org.junit.Test;import java.util.List;import static org.junit.Assert.*;
/** Manager 组合 Lease 与生命周期后，关闭和激活必须保持受控状态。 */
public class KnowledgeStoreManagerTest {
 static final class Gateway implements KnowledgeStoreGateway {boolean closed;public KnowledgeStoreMetadataEntity metadata(){return new KnowledgeStoreMetadataEntity();}public List<KnowledgeChunkEntity> childChunksByIds(List<String> ids){return List.of();}public List<KnowledgeChunkEntity> childChunksByEntityIds(List<Long> ids){return List.of();}public List<KnowledgeChunkEntity> nearestChildren(float[] vector,int limit){return List.of();}public List<LexicalTermEntity> lexicalTermsByTerms(List<String> terms){return List.of();}public void close(){closed=true;}}
 @Test public void activatesGatewayAndClosesItAfterManagerClose(){var manager=new KnowledgeStoreManager();var gateway=new Gateway();manager.installStarted();manager.activate(gateway,"1.0","scope");assertEquals(KnowledgeStoreState.READY,manager.snapshot().state());try(var lease=manager.acquire()){assertSame(gateway,lease.value());}manager.close();assertTrue(gateway.closed);assertEquals(KnowledgeStoreState.CLOSED,manager.snapshot().state());}
}
