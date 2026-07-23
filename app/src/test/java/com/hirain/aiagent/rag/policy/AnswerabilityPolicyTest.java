package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.*;import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** 任何缺 Locator、UNKNOWN、取消或超时都必须阻止 answerable。 */
public class AnswerabilityPolicyTest {
 @Test public void requiresApplicableEvidenceWithReliableLocator(){AnswerabilityPolicy p=new AnswerabilityPolicy();RetrievalEvidence good=evidence(Applicability.EXACT,new SourceLocator(SourceFormat.PDF,List.of("H"),1,1,null,null,null,0,0,0));assertTrue(p.answerable(List.of(good),false,false,false));assertFalse(p.answerable(List.of(good),true,false,false));assertFalse(p.answerable(List.of(evidence(Applicability.UNKNOWN,good.sourceLocator())),false,false,false));assertFalse(p.answerable(List.of(evidence(Applicability.EXACT,null)),false,false,false));}
 static RetrievalEvidence evidence(Applicability a,SourceLocator l){return new RetrievalEvidence("E","C","P","可靠内容","D","标题","1","","",l,null,null,null,null,null,null,null,null,List.of("LEXICAL"),a);}
}
