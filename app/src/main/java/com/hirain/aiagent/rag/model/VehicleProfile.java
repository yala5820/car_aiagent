package com.hirain.aiagent.rag.model;

import java.util.Objects;

/** 仅承载可信 Provider 的车辆事实，严禁由用户输入、extraContext 或 Tool 参数构造。 */
public record VehicleProfile(String vehicleModel, String modelYear, String region, String softwareVersion,
                             String configurationCode, long updatedAtMs) {
    public VehicleProfile {
        require(vehicleModel); require(modelYear); require(region); require(softwareVersion); require(configurationCode);
        if (updatedAtMs < 0) throw new IllegalArgumentException("updatedAtMs 不能为负数");
    }
    private static void require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("VehicleProfile 字段不能为空"); }
}
