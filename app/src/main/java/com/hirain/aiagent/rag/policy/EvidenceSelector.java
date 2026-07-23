package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.RetrievalEvidence;import java.util.List;
/** Evidence 进入模型前的唯一选择入口：先去重，再按既有排序应用语义 Token 预算。 */
public final class EvidenceSelector { private final EvidenceDeduplicator deduplicator;private final EvidenceBudgetPolicy budget;public EvidenceSelector(EvidenceDeduplicator d,EvidenceBudgetPolicy b){deduplicator=d;budget=b;}public List<RetrievalEvidence> select(List<RetrievalEvidence> values){return budget.select(deduplicator.deduplicate(values));} }
