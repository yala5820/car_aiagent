package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.*;import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** 同一内部 Evidence/Parent/内容的重复检索条目不得重复进入模型预算。 */
public class EvidenceDeduplicatorTest {
 @Test public void removesDuplicateEvidence(){RetrievalEvidence first=evidence("E","相同内容");RetrievalEvidence duplicate=evidence("E","相同内容");RetrievalEvidence different=evidence("E2","不同内容");assertEquals(2,new EvidenceDeduplicator().deduplicate(List.of(first,duplicate,different)).size());}
 static RetrievalEvidence evidence(String id,String content){SourceLocator l=new SourceLocator(SourceFormat.PDF,List.of(),1,1,null,null,null,0,0,0);return new RetrievalEvidence(id,"C","P",content,"D","T","1","","",l,null,null,null,null,null,null,null,null,List.of(),Applicability.EXACT);}
}
