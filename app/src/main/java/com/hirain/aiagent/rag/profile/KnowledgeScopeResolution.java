package com.hirain.aiagent.rag.profile;
import com.hirain.aiagent.rag.model.RagFailureReason;
/** Scope 解析结果；失败不猜测相近车型或版本。 */
public record KnowledgeScopeResolution(String knowledgeScopeId, RagFailureReason failureReason) {
 public boolean resolved() { return failureReason == null; }
}
