package com.hirain.aiagent.rag.retrieval;

import com.hirain.aiagent.rag.model.VehicleProfile;
import com.hirain.aiagent.rag.store.entity.KnowledgeChunkEntity;

/** 五维 Metadata 只允许精确值或 `*` 通配；未知 Profile 不得放行具体车辆资料。 */
public final class MetadataEligibilityPolicy {
    public MetadataEligibilityResult evaluate(KnowledgeChunkEntity chunk, VehicleProfile profile) {
        if (chunk == null) return MetadataEligibilityResult.rejected("METADATA_MISSING");
        if (!"CHILD".equals(chunk.chunkLevel)) return MetadataEligibilityResult.rejected("CHUNK_NOT_CHILD");
        if (profile == null) return wildcard(chunk.vehicleModel) && wildcard(chunk.modelYear) && wildcard(chunk.region) && wildcard(chunk.softwareVersion) && wildcard(chunk.configurationCode) ? MetadataEligibilityResult.accepted() : MetadataEligibilityResult.rejected("PROFILE_INCOMPLETE");
        if (!matches(chunk.vehicleModel, profile.vehicleModel())) return MetadataEligibilityResult.rejected("METADATA_VEHICLE_MODEL_MISMATCH");
        if (!matches(chunk.modelYear, profile.modelYear())) return MetadataEligibilityResult.rejected("METADATA_MODEL_YEAR_MISMATCH");
        if (!matches(chunk.region, profile.region())) return MetadataEligibilityResult.rejected("METADATA_REGION_MISMATCH");
        if (!matches(chunk.softwareVersion, profile.softwareVersion())) return MetadataEligibilityResult.rejected("METADATA_SOFTWARE_VERSION_MISMATCH");
        return matches(chunk.configurationCode, profile.configurationCode()) ? MetadataEligibilityResult.accepted() : MetadataEligibilityResult.rejected("METADATA_CONFIGURATION_MISMATCH");
    }
    private static boolean matches(String actual, String expected) { return wildcard(actual) || (actual != null && actual.equals(expected)); }
    private static boolean wildcard(String value) { return "*".equals(value); }
}
