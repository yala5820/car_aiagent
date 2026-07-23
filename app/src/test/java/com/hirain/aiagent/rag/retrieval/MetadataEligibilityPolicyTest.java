package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;
import org.junit.Test;
import static org.junit.Assert.*;

/** 五维 Scope/Metadata 必须在融合前严格过滤，通配只放行明确声明的 `*`。 */
public class MetadataEligibilityPolicyTest {
    @Test public void acceptsExactAndWildcardButRejectsRegionMismatch() { MetadataEligibilityPolicy policy = new MetadataEligibilityPolicy(); VehicleProfile profile = new VehicleProfile("M1", "2026", "CN", "1.0", "BASE", 0); KnowledgeChunkEntity chunk = child(); assertTrue(policy.evaluate(chunk, profile).eligible()); chunk.region = "*"; assertTrue(policy.evaluate(chunk, profile).eligible()); chunk.region = "US"; MetadataEligibilityResult result = policy.evaluate(chunk, profile); assertFalse(result.eligible()); assertEquals("METADATA_REGION_MISMATCH", result.reasonCode()); }
    @Test public void missingProfileOnlyAcceptsAllWildcardChunk() { KnowledgeChunkEntity chunk = child(); assertFalse(new MetadataEligibilityPolicy().evaluate(chunk, null).eligible()); chunk.vehicleModel="*";chunk.modelYear="*";chunk.region="*";chunk.softwareVersion="*";chunk.configurationCode="*";assertTrue(new MetadataEligibilityPolicy().evaluate(chunk,null).eligible()); }
    private static KnowledgeChunkEntity child(){KnowledgeChunkEntity value=new KnowledgeChunkEntity();value.chunkLevel="CHILD";value.vehicleModel="M1";value.modelYear="2026";value.region="CN";value.softwareVersion="1.0";value.configurationCode="BASE";return value;}
}
