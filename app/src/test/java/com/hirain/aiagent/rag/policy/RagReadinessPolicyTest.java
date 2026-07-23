package com.hirain.aiagent.rag.policy;
import org.junit.Test;import static org.junit.Assert.*;
/** Release 不能因开发 Fixture 或未批准阈值而意外开放 RAG。 */
public class RagReadinessPolicyTest { @Test public void releaseRejectsTestOnly(){RagReadinessPolicy p=new RagReadinessPolicy();assertFalse(p.ready(false,RagReadinessPolicy.Approval.TEST_ONLY,RagReadinessPolicy.Approval.APPROVED));assertFalse(p.ready(false,RagReadinessPolicy.Approval.APPROVED,RagReadinessPolicy.Approval.TEST_ONLY));assertTrue(p.ready(false,RagReadinessPolicy.Approval.APPROVED,RagReadinessPolicy.Approval.APPROVED));assertTrue(p.ready(true,RagReadinessPolicy.Approval.TEST_ONLY,RagReadinessPolicy.Approval.TEST_ONLY));} }
