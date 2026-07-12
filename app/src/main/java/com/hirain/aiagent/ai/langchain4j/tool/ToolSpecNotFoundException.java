package com.hirain.aiagent.ai.langchain4j.tool;

/**
 * 工具规格查询异常 — 指定的 toolName 在 ToolRegistry 中不存在。
 */
public class ToolSpecNotFoundException extends RuntimeException {

    private final String toolName;

    public ToolSpecNotFoundException(String toolName) {
        super("ToolSpecification not found for: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() { return toolName; }
}
