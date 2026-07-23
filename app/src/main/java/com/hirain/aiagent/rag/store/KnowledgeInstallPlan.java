package com.hirain.aiagent.rag.store;

import com.hirain.aiagent.rag.model.VehicleProfile;

/** 一次安装的不可变输入；Asset 根目录固定由应用代码提供，禁止来自模型或调用方。 */
public record KnowledgeInstallPlan(KnowledgeAssetSource assets, String assetRoot, VehicleProfile vehicleProfile, String installId) {
    public KnowledgeInstallPlan {
        if (assets == null || assetRoot == null || !assetRoot.matches("[A-Za-z0-9/_-]+") || vehicleProfile == null) throw new IllegalArgumentException("INSTALL_PLAN_INVALID");
        KnowledgeStorageLayout.safeKey(installId);
    }
}
