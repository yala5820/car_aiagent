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
    public static final String TOOL_SAFETY_GUARD_COUNT = "tool.safety_guard_count";
    public static final String TOOL_DISPATCH_TARGET = "tool.dispatch_target";
    public static final String TOOL_DISPATCH_SUCCESS = "tool.dispatch_success";
    public static final String TOOL_DISPATCH_DURATION_MS = "tool.dispatch_duration_ms";
    public static final String TOOL_DISPATCH_TARGET_CLASS = "tool.target_class";
    public static final String TOOL_DISPATCH_TARGET_METHOD = "tool.target_method";
    public static final String TOOL_ARGUMENT_PARSE_SUCCESS = "tool.argument_parse_success";
    public static final String TOOL_INVOKE_SUCCESS = "tool.invoke_success";
    public static final String TOOL_WRITEBACK_RESULT = "tool.writeback_result";
    public static final String TOOL_WRITEBACK_TO_MEMORY = "tool.writeback_to_memory";

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

    // ── Context ──

    public static final String CONTEXT_ENABLED = "agent.context.enabled";
    public static final String CONTEXT_MODE = "agent.context.mode";
    public static final String CONTEXT_PROVIDER_COUNT = "agent.context.provider_count";
    public static final String CONTEXT_PROVIDERS = "agent.context.providers";
    public static final String CONTEXT_SELECTED_TOOL_COUNT = "agent.context.selected_tool_count";
    public static final String CONTEXT_SELECTED_TOOL_NAMES = "agent.context.selected_tool_names";
    public static final String CONTEXT_SECTION_COUNT = "agent.context.section_count";
    public static final String CONTEXT_TOKEN_ESTIMATE = "agent.context.token_estimate";
    public static final String CONTEXT_FALLBACK_USED = "agent.context.fallback_used";
    public static final String CONTEXT_BUILD_MS = "agent.context.build_ms";
    public static final String CONTEXT_ERROR = "agent.context.error";

    // ── Context Fragment / Message / Toolset ──

    public static final String FRAGMENT_SOURCE_KEY = "fragment.source_key";
    public static final String FRAGMENT_TARGET_AREA = "fragment.target_area";
    public static final String FRAGMENT_PROVIDER = "fragment.provider";
    public static final String FRAGMENT_INCLUDED_IN_MODEL = "fragment.included_in_model";
    public static final String FRAGMENT_CONTENT = "fragment.content";
    public static final String MESSAGE_SOURCE = "message.source";
    public static final String MESSAGE_PROVIDER = "message.provider";
    public static final String MESSAGE_COUNT = "message.count";
    public static final String TOOLSET_PROVIDER = "toolset.provider";
    public static final String TOOLSET_TOOL_COUNT = "toolset.tool_count";
    public static final String TOOLSET_TOOL_NAMES = "toolset.tool_names";

    private TraceAttributeKeys() {
    }
}
