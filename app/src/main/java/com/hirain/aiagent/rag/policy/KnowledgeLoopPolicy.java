package com.hirain.aiagent.rag.policy;
/** 强制查证策略只依据本请求状态；没有可靠 Evidence 时禁止模型以自由文本结束 REQUIRED 请求。 */
public final class KnowledgeLoopPolicy {
 public KnowledgeLoopDecision beforeModel(KnowledgeIntentDecision decision,KnowledgeRequestState state){if(decision!=null&&decision.requirement()==KnowledgeRequirement.REQUIRED&&state!=null&&!"EVIDENCE".equals(state.lastEvidenceStatus()))return new KnowledgeLoopDecision(true,"KNOWLEDGE_EVIDENCE_REQUIRED");return new KnowledgeLoopDecision(false,"KNOWLEDGE_EVIDENCE_AVAILABLE_OR_NOT_REQUIRED");}
 public boolean evidenceExhausted(KnowledgeIntentDecision decision,KnowledgeRequestState state){return decision!=null&&decision.requirement()==KnowledgeRequirement.REQUIRED&&state!=null&&!"EVIDENCE".equals(state.lastEvidenceStatus())&&state.totalInvocationCount()>=2;}
}
