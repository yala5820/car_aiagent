package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.*;import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** Mapper 只能输出稳定白名单，内部检索诊断不得成为模型上下文。 */
public class VehicleKnowledgeToolResultMapperTest {
 @Test public void mapsOnlyAnswerableEvidence(){SourceLocator l=new SourceLocator(SourceFormat.PDF,List.of(),1,1,null,null,null,0,0,0);RetrievalEvidence e=new RetrievalEvidence("internal","chunk","parent","内容","doc","标题","1","","",l,0.1,1,2d,1,3d,1,4d,1,List.of("DENSE"),Applicability.EXACT);RagResult r=new RagResult(1,RagStatus.SUCCESS,true,"原始问题","normalized",RetrievalMode.HYBRID_RERANKED,List.of(e),List.of(),null,1,"secret diagnostic");VehicleKnowledgeToolResult result=new VehicleKnowledgeToolResultMapper().map(r,new EvidenceIdAllocator());assertEquals("E1",result.evidence().get(0).evidenceId());assertEquals("原始问题",result.query());assertEquals(1,result.evidence().size());}
 @Test public void neverMapsEvidenceForUnanswerableResult(){RagResult r=new RagResult(1,RagStatus.NO_EVIDENCE,false,"q","n",null,List.of(),List.of(),RagFailureReason.EMBEDDING_UNAVAILABLE,1,"internal");assertTrue(new VehicleKnowledgeToolResultMapper().map(r,new EvidenceIdAllocator()).evidence().isEmpty());}
}
