package com.hirain.aiagent.safety;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 单次 Tool 安全审核的轻量输入。
 * <p>
 * 这里只保存 Tool 名称和已经解析的参数，不引入完整 AgentLoopContext，
 * 使安全规则与人格、记忆、Prompt 和循环状态保持解耦。
 */
public final class SafetyCheckContext {

    private final String toolName;
    private final JsonObject arguments;

    public SafetyCheckContext(String toolName, JsonObject arguments) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    public String toolName() {
        return toolName;
    }

    /**
     * 返回本次审核使用的标准化参数。
     * 规则只读取参数，不应修改该对象。
     */
    public JsonObject arguments() {
        return arguments;
    }
}
