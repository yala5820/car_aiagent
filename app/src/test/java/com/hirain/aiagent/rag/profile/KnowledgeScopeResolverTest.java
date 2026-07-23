package com.hirain.aiagent.rag.profile;

import com.hirain.aiagent.rag.model.RagFailureReason;
import com.hirain.aiagent.rag.model.VehicleProfile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 同一 Profile 必须生成离线 Golden 指定的 Scope ID；不完整字段不得降级猜测。 */
public class KnowledgeScopeResolverTest {
    @Test
    public void matchesSharedDemoScopeGolden() {
        var profile = new VehicleProfile("DEMO_MODEL", "2026", "CN", "DEMO_VERSION", "DEFAULT", 1L);
        var resolution = new KnowledgeScopeResolver().resolve(profile);
        assertTrue(resolution.resolved());
        assertEquals("demo-model-2026-cn-demo-version-default", resolution.knowledgeScopeId());
    }

    @Test
    public void rejectsIncompleteProfileWithoutFallback() {
        var profile = new VehicleProfile("DEMO_MODEL", "2026", "CN", "DEMO_VERSION", "DEFAULT", 1L);
        // 记录类型会拒绝空字段；null Profile 仍必须映射为稳定的 PROFILE_INCOMPLETE。
        var resolution = new KnowledgeScopeResolver().resolve(null);
        assertFalse(resolution.resolved());
        assertEquals(RagFailureReason.PROFILE_INCOMPLETE, resolution.failureReason());
    }
}
