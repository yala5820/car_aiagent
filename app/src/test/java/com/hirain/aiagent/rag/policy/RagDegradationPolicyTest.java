package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.RetrievalMode;import org.junit.Test;import static org.junit.Assert.*;
/** Dense 不可用不能制造无依据的 Lexical 降级。 */
public class RagDegradationPolicyTest { @Test public void permitsOnlyDocumentedDegradations(){RagDegradationPolicy p=new RagDegradationPolicy();assertEquals(RetrievalMode.LEXICAL_ONLY,p.embeddingFailed(true));assertNull(p.embeddingFailed(false));assertEquals(RetrievalMode.HYBRID_FUSION_ONLY,p.rerankFailed());} }
