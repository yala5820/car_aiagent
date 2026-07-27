package com.hirain.aiagent.rag.model;

/** 模型可见 Evidence，仅携带作答与引用所需事实。 */
public record VehicleKnowledgeEvidence(String evidenceId, String content, String documentTitle, String documentVersion,
                                       String sectionPath, SourceLocator sourceLocator, Applicability applicability,
                                       RetrievalConfidence retrievalConfidence) {
    public VehicleKnowledgeEvidence(String evidenceId, String content, String documentTitle, String documentVersion,
                                    SourceLocator sourceLocator, Applicability applicability) {
        this(evidenceId, content, documentTitle, documentVersion, null, sourceLocator, applicability,
                RetrievalConfidence.UNASSESSED);
    }
}
