package com.hirain.aiagent.trace;

public final class TraceAttributeKeys {

    public static final String REQUEST_ID = "request.id";
    public static final String SESSION_ID = "session.id";
    public static final String SOURCE_APP = "source.app";
    public static final String INPUT_TYPE = "input.type";
    public static final String USER_INPUT = "user.input";
    public static final String USER_INPUT_LENGTH = "user.input.length";

    public static final String AGENT_PERSONA = "agent.persona";
    public static final String AGENT_ITERATION = "agent.iteration";
    public static final String AGENT_MAX_ITERATIONS = "agent.max_iterations";

    public static final String PROMPT_SYSTEM = "prompt.system";
    public static final String PROMPT_TRANSIENT_MESSAGES = "prompt.transient_messages";
    public static final String PROMPT_CHAT_MESSAGES = "prompt.chat_messages";
    public static final String PROMPT_MESSAGE_COUNT = "prompt.message_count";
    public static final String PROMPT_TOOL_SPECS = "prompt.tool_specs";
    public static final String PROMPT_TOOL_SPEC_COUNT = "prompt.tool_spec_count";

    public static final String GEN_AI_PROVIDER = "gen_ai.provider";
    public static final String GEN_AI_MODEL = "gen_ai.model";
    public static final String GEN_AI_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String GEN_AI_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
    public static final String GEN_AI_OUTPUT = "gen_ai.output";
    public static final String GEN_AI_TOOL_CALLS = "gen_ai.tool_calls";

    public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
    public static final String HTTP_REQUEST_METHOD = "http.request.method";
    public static final String URL_FULL = "url.full";
    public static final String SERVER_ADDRESS = "server.address";
    public static final String HTTP_DURATION_MS = "http.duration_ms";

    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_ARGUMENTS = "tool.arguments";
    public static final String TOOL_OUTPUT = "tool.output";
    public static final String TOOL_SUCCESS = "tool.success";
    public static final String TOOL_SAFETY_VETO = "tool.safety_veto";
    public static final String TOOL_SAFETY_VETO_REASON = "tool.safety_veto_reason";

    public static final String MEMORY_OPERATION = "memory.operation";
    public static final String MEMORY_INPUT_CHARS = "memory.input_chars";
    public static final String MEMORY_OUTPUT_CHARS = "memory.output_chars";
    public static final String MEMORY_CANDIDATE_COUNT = "memory.candidate_count";
    public static final String MEMORY_COMPRESSED = "memory.compressed";
    public static final String MEMORY_PROMPT = "memory.prompt";
    public static final String MEMORY_OUTPUT = "memory.output";

    public static final String CLIENT_MESSAGE_ID = "client_message.id";

    public static final String RESPONSE_SUCCESS = "response.success";
    public static final String RESPONSE_ERROR_TYPE = "response.error_type";
    public static final String RESPONSE_TEXT_LENGTH = "response.text.length";

    public static final String ERROR_TYPE = "error.type";
    public static final String ERROR_MESSAGE = "error.message";

    private TraceAttributeKeys() {
    }
}
