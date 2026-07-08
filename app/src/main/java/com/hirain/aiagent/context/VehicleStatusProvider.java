package com.hirain.aiagent.context;

/**
 * 车辆状态快照提供者。
 * <p>
 * 设计原因：Context 模块只需要读取一份车辆状态字符串，不应直接依赖
 * core.preprocessor.VehicleStatusPreProcessor 的内部接口，避免 context -> core 的包依赖倒挂。
 */
@FunctionalInterface
public interface VehicleStatusProvider {
    String getVehicleStatus();
}
