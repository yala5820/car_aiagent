package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.intentrouter.*;import java.util.List;import org.junit.Test;import static org.junit.Assert.*;
/** 规则测试锁定 V1 只输出 NONE/REQUIRED，并保护动作请求不被错误升级。 */
public class RuleBasedKnowledgeNeedDetectorTest {
 private final RuleBasedKnowledgeNeedDetector detector=new RuleBasedKnowledgeNeedDetector();
 @Test public void requiresOfficialVehicleKnowledgeButNeverOptional(){assertEquals(KnowledgeRequirement.REQUIRED,detector.decide("请查用户手册中空调的使用条件",chat()).requirement());assertEquals(KnowledgeRequirement.REQUIRED,detector.decide("P0123 故障码是什么意思",chat()).requirement());assertEquals(KnowledgeRequirement.NONE,detector.decide("打开空调",vehicle()).requirement());assertNotEquals(KnowledgeRequirement.OPTIONAL,detector.decide("前方有什么",chat()).requirement());}
 @Test public void routesVehicleMaintenanceProcedureButNotGenericProcedure(){assertEquals(KnowledgeRequirement.REQUIRED,detector.decide("Model Y如何更换空调滤清器",chat()).requirement());assertEquals("KNOWLEDGE_MAINTENANCE_PROCEDURE",detector.decide("Model Y如何更换空调滤清器",chat()).reasonCode());assertEquals(KnowledgeRequirement.REQUIRED,detector.decide("How to replace ModelY cabin air filter according to owner manual?",chat()).requirement());assertEquals(KnowledgeRequirement.NONE,detector.decide("如何更换头像",chat()).requirement());}
 @Test public void identifiesKnowledgeVehicleCompound(){assertFalse(detector.decide("空调使用条件是什么",vehicle()).compoundIntentDetected());KnowledgeIntentDecision decision=detector.decide("查一下空调使用条件，然后打开空调",vehicle());assertEquals(KnowledgeRequirement.REQUIRED,decision.requirement());assertTrue(decision.compoundIntentDetected());}
 private static IntentResult chat(){return IntentResult.of(IntentTag.CHAT,IntentConfidence.LOW,List.of(),"","TEXT","test");}
 private static IntentResult vehicle(){return IntentResult.of(IntentTag.VEHICLE_AC,IntentConfidence.HIGH,List.of(),"","TEXT","test");}
}
