package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.rag.model.RetrievalEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.hirain.aiagent.rag.model.VehicleKnowledgeEvidence;

/**
 * 随 RequestSession 创建并随其释放的知识调用状态。全部状态转换同步，避免 Tool 在同一
 * worker 上重入时突破一次迭代/两次请求的硬上限；不得将此对象放进静态全局 Map。
 */
public final class KnowledgeRequestState {
    private int currentIteration = -1;
    private int iterationInvocationCount;
    private int totalInvocationCount;
    private int lastInvocationIteration = -1;
    private final Map<String, Boolean> queryHashes = new LinkedHashMap<>();
    private final Map<String, String> evidenceIds = new LinkedHashMap<>();
    private final Map<String, VehicleKnowledgeEvidence> citationEvidence = new LinkedHashMap<>();
    private final KnowledgeTurnBuffer turnBuffer = new KnowledgeTurnBuffer();
    private int nextEvidenceOrdinal = 1;
    private String lastEvidenceStatus = "NONE";
    private String degradedMode = "NONE";

    public synchronized void beginIteration(int iteration) {
        if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
        if (iteration < currentIteration) throw new IllegalArgumentException("iteration must not go backwards");
        if (iteration != currentIteration) { currentIteration = iteration; iterationInvocationCount = 0; }
    }
    public synchronized KnowledgeInvocationDecision tryBeginInvocation(int iteration, String normalizedQuery) {
        beginIteration(iteration);
        if (iterationInvocationCount >= 1) return KnowledgeInvocationDecision.deny("MULTIPLE_RAG_CALLS_IN_ITERATION");
        if (totalInvocationCount >= 2) return KnowledgeInvocationDecision.deny("RAG_INVOCATION_LIMIT_REACHED");
        String hash = sha256(normalizedQuery);
        if (queryHashes.containsKey(hash)) return KnowledgeInvocationDecision.deny("DUPLICATE_QUERY");
        if (totalInvocationCount > 0 && iteration <= lastInvocationIteration) return KnowledgeInvocationDecision.deny("RAG_SECOND_CALL_REQUIRES_LATER_ITERATION");
        queryHashes.put(hash, Boolean.TRUE); iterationInvocationCount++; totalInvocationCount++; lastInvocationIteration = iteration;
        return KnowledgeInvocationDecision.allow();
    }
    /** 相同 document/chunk/locator 在第二次查询中稳定复用短 ID，避免同源出现多个模型引用。 */
    public synchronized String evidenceIdFor(RetrievalEvidence evidence) {
        if (evidence == null) throw new IllegalArgumentException("evidence must not be null");
        String key = String.valueOf(evidence.documentId()) + "|" + String.valueOf(evidence.chunkId()) + "|" + String.valueOf(evidence.sourceLocator());
        return evidenceIds.computeIfAbsent(key, ignored -> "E" + nextEvidenceOrdinal++);
    }
    public synchronized void recordOutcome(String evidenceStatus, String mode) { lastEvidenceStatus=evidenceStatus == null ? "NONE" : evidenceStatus; degradedMode=mode == null ? "NONE" : mode; }
    public synchronized int totalInvocationCount() { return totalInvocationCount; }
    public synchronized int currentIteration() { return currentIteration; }
    public synchronized int iterationInvocationCount() { return iterationInvocationCount; }
    public synchronized String lastEvidenceStatus() { return lastEvidenceStatus; }
    public synchronized String degradedMode() { return degradedMode; }
    public KnowledgeTurnBuffer turnBuffer() { return turnBuffer; }
    public synchronized void registerCitationEvidence(java.util.List<VehicleKnowledgeEvidence> values) { if(values!=null)for(VehicleKnowledgeEvidence value:values) if(value!=null&&value.evidenceId()!=null)citationEvidence.put(value.evidenceId(),value); }
    public synchronized Map<String, VehicleKnowledgeEvidence> citationEvidenceMap() { return Map.copyOf(citationEvidence); }
    private static String sha256(String value) { try { byte[] digest=MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte item:digest)out.append(String.format("%02x",item));return out.toString(); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); } }
}
