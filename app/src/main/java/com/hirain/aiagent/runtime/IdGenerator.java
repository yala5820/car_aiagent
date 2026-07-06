package com.hirain.aiagent.runtime;

/**
 * requestId 生成接口。
 * 设计原因：生产环境使用 UUID，单元测试使用固定值验证映射行为。
 */
public interface IdGenerator {
    String newRequestId();
}
