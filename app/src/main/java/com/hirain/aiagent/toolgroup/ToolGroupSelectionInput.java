package com.hirain.aiagent.toolgroup;

import com.hirain.aiagent.intentrouter.IntentResult;

/**
 * ToolGroup 选择输入 — 封装选择器所需的全部上下文。
 * <p>
 * 设计原因：当前选择器只用 intentResult + userInput 做决策，
 * 未来小 LLM / 子 agent 选择器可能需要 inputType、userId、sessionId、personaId
 * 等额外上下文。提前用不可变对象封装，避免以后不断扩展 select() 参数列表。
 * <p>
 * 不依赖 Android 类型，不依赖 LangChain4j 类型。
 */
public final class ToolGroupSelectionInput {

    private final IntentResult intentResult;
    private final String userInput;
    private final String inputType;
    private final String userId;
    private final String sessionId;
    private final String personaId;

    private ToolGroupSelectionInput(Builder builder) {
        this.intentResult = builder.intentResult;
        this.userInput = builder.userInput;
        this.inputType = builder.inputType;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.personaId = builder.personaId;
    }

    /**
     * 最小便捷工厂方法 — 兼容当前选择器调用方式。
     * inputType 默认 "TEXT"，personaId 默认 "chat"，其余 ID 字段为 null。
     */
    public static ToolGroupSelectionInput of(IntentResult intentResult, String userInput) {
        return new Builder()
                .intentResult(intentResult)
                .userInput(userInput)
                .inputType("TEXT")
                .personaId("chat")
                .build();
    }

    /** Builder 入口 — 用于未来携带更多上下文。 */
    public static Builder builder() {
        return new Builder();
    }

    // ── 读取器 ──

    public IntentResult intentResult() { return intentResult; }
    public String userInput() { return userInput; }
    public String inputType() { return inputType; }
    public String userId() { return userId; }
    public String sessionId() { return sessionId; }
    public String personaId() { return personaId; }

    // ── Builder ──

    public static final class Builder {
        private IntentResult intentResult;
        private String userInput;
        private String inputType = "TEXT";
        private String userId;
        private String sessionId;
        private String personaId = "chat";

        private Builder() {}

        public Builder intentResult(IntentResult v) { this.intentResult = v; return this; }
        public Builder userInput(String v) { this.userInput = v; return this; }
        public Builder inputType(String v) { if (v != null) this.inputType = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder personaId(String v) { if (v != null) this.personaId = v; return this; }

        public ToolGroupSelectionInput build() {
            return new ToolGroupSelectionInput(this);
        }
    }
}
