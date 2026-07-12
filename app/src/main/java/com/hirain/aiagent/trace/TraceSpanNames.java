package com.hirain.aiagent.trace;

public final class TraceSpanNames {

    public static final String AGENT_REQUEST = "agent.request";
    public static final String AGENT_LOOP = "agent.loop";
    public static final String PROMPT_ASSEMBLY = "prompt.assembly";
    public static final String GEN_AI_CHAT = "gen_ai.chat";
    public static final String TOOL_EXECUTE = "tool.execute";
    public static final String MEMORY_EXTRACT = "memory.extract";
    public static final String MEMORY_COMPRESS = "memory.compress";
    public static final String RESPONSE_DISPATCH = "response.dispatch";
    public static final String CONTEXT_PREPARE = "context.prepare";
    public static final String CONTEXT_ASSEMBLE = "context.assemble";

    private TraceSpanNames() {
    }
}
