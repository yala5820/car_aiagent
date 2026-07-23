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
    public static final String AGENT_ITERATION = "agent.iteration";
    public static final String CONTEXT_FRAGMENT = "context.fragment";
    public static final String CONTEXT_MESSAGE = "context.message";
    public static final String CONTEXT_TOOLSET = "context.toolset";
    public static final String TOOL_SAFETY_CHECK = "tool.safety_check";
    public static final String TOOL_DISPATCH = "tool.dispatch";
    public static final String TOOL_RESULT_WRITEBACK = "tool.result_writeback";
    public static final String VISION_IMAGE_LOAD = "vision.image.load";
    public static final String VISION_MODEL = "vision.model";
    public static final String VISION_RESULT = "vision.result";
    public static final String RAG_RETRIEVE = "rag.retrieve";

    private TraceSpanNames() {
    }
}
