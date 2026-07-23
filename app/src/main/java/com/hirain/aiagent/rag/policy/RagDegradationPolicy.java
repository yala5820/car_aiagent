package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.RetrievalMode;
/** 仅允许设计文件明示的两种降级；不得因 Dense 失败放宽 Lexical 证据门槛。 */
public final class RagDegradationPolicy { public RetrievalMode embeddingFailed(boolean lexicalEligible){return lexicalEligible?RetrievalMode.LEXICAL_ONLY:null;} public RetrievalMode rerankFailed(){return RetrievalMode.HYBRID_FUSION_ONLY;} }
