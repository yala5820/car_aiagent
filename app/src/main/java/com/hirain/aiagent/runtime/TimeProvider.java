package com.hirain.aiagent.runtime;

/**
 * 时间来源接口。
 * 设计原因：RuntimeResult.timestampMs 需要在单元测试中可预测。
 */
public interface TimeProvider {
    long nowMillis();
}
