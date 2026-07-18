package com.hirain.aiagent.core.factory;

import android.content.Context;

import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.ai.langchain4j.tool.ToolRegistry;
import com.hirain.aiagent.core.AgentConfig;
import com.hirain.aiagent.core.collector.DirectTextCollector;
import com.hirain.aiagent.core.collector.SummarizeMergeCollector;
import com.hirain.aiagent.core.model.Lc4jModelCaller;
import com.hirain.aiagent.core.postprocessor.NoOpPostProcessor;
import com.hirain.aiagent.core.postprocessor.SceneActionMergePostProcessor;
import com.hirain.aiagent.core.preprocessor.ActiveControlPreProcessor;
import com.hirain.aiagent.core.preprocessor.SceneContextPreProcessor;
import com.hirain.aiagent.core.preprocessor.TimeContextPreProcessor;
import com.hirain.aiagent.core.preprocessor.VehicleStatusPreProcessor;
import com.hirain.aiagent.core.postprocessor.MemoryPostProcessor;
import com.hirain.aiagent.core.postprocessor.VisionGroundingPostProcessor;
import com.hirain.aiagent.core.terminator.NoToolCallTerminator;
import com.hirain.aiagent.engines.scenematch.SceneMatch;
import com.hirain.aiagent.memory.MemoryOrchestrator;
import com.hirain.aiagent.prompt.PromptConstants;
import com.hirain.aiagent.runtime.RequestCallRegistry;
import com.hirain.aiagent.trace.TracingOkHttpInterceptor;
import com.hirain.aiagent.prompt.PromptManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.openai.OpenAiChatModel;
import langchain4j.http_client_ok.OkHttpClient;
import langchain4j.http_client_ok.OkHttpClientBuilder;

/**
 * AgentConfig 工厂 — 提供三个预设 Persona 的配置构造方法。
 * <p>
 * 这是"人格"的配置层。新增一个 Persona 只需新增一个静态方法。
 */
public class AgentConfigFactory {

    private AgentConfigFactory() {}

    // ── 预设 Persona ──

    /**
     * 对话助手人格：持久记忆、全部工具、多轮迭代、直接文本输出。
     * 对应 ChatServer 的功能。
     */
    public static AgentConfig createChatPersona(Context context,
                                                 PromptManager promptManager,
                                                 MemoryOrchestrator memoryOrchestrator,
                                                 ToolRegistry toolRegistry,
                                                 VehicleStatusPreProcessor.VehicleStatusProvider statusProvider) {
        return AgentConfig.builder("chat")
                .modelName("qwen-turbo")
                .systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_DEFAULT)
                .maxIterations(10)
                .maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                // session-scoped 主路径由 MemoryOrchestrator.chatMemoryForSession 决定；此字段仅保留给 legacy 路径
                .chatMemoryStoreId("FallbackChatMemory")
                .preProcessors(List.of(
                        new VehicleStatusPreProcessor(promptManager, statusProvider),
                        new TimeContextPreProcessor()))
                .modelCaller(new Lc4jModelCaller(buildQwenTurbo()))
                .toolExecutor(toolRegistry::dispatch)
                .toolSubset(null)
                .postProcessors(List.of(
                        new NoOpPostProcessor(),
                        new MemoryPostProcessor(),
                        new VisionGroundingPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 场景服务人格：临时记忆、场景专属工具子集（白名单）、
     * 单工具轮 + 汇总合并输出。对应 SceneServer 的功能。
     */
    public static AgentConfig createScenePersona(Context context,
                                                  PromptManager promptManager,
                                                  ToolRegistry toolRegistry,
                                                  VehicleStatusPreProcessor.VehicleStatusProvider statusProvider,
                                                  SceneMatch.Scene scene) {
        return AgentConfig.builder("scene")
                .modelName("qwen-flash")
                .systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_SCENE)
                .maxIterations(2)
                .maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.EPHEMERAL)
                .chatMemoryStoreId(null)
                .preProcessors(List.of(
                        new SceneContextPreProcessor(promptManager),
                        new VehicleStatusPreProcessor(promptManager, statusProvider),
                        new ActiveControlPreProcessor(promptManager)))
                .modelCaller(new Lc4jModelCaller(buildQwenFlash()))
                .toolExecutor(toolRegistry::dispatch)
                .toolSubset(filterSceneTools(toolRegistry, scene.name))
                .postProcessors(List.of(
                        new SceneActionMergePostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new SummarizeMergeCollector(
                        promptManager, buildQwenFlash(), 80))
                .timeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * TEXT 人格统一入口 — 根据 personaId 选择系统提示词和记忆存储 ID。
     * <p>
     * 支持 chat / friendly / concise 三种人格，不开放 scene / vision_qa。
     */
    public static AgentConfig createTextPersona(Context context,
                                                 PromptManager promptManager,
                                                 MemoryOrchestrator memoryOrchestrator,
                                                 ToolRegistry toolRegistry,
                                                 VehicleStatusPreProcessor.VehicleStatusProvider statusProvider,
                                                 RequestCallRegistry requestCallRegistry,
                                                 String personaId) {
        String template = switchPersonaTemplate(personaId);
        // session-scoped 主路径由 MemoryOrchestrator.chatMemoryForSession(sessionId, maxMessages) 决定；
        // 此字段仅保留给 legacy / 非 TEXT 兼容路径
        String memoryId = "FallbackChatMemory";
        return AgentConfig.builder(normalizeTextPersona(personaId))
                .modelName("qwen-turbo")
                .systemPromptTemplateName(template)
                .maxIterations(10)
                .maxMemoryMessages(50)
                .memoryPolicy(AgentConfig.MemoryPolicy.PERSISTENT)
                .chatMemoryStoreId(memoryId)
                .preProcessors(List.of())
                .modelCaller(new Lc4jModelCaller(buildQwenTurbo(requestCallRegistry)))
                .toolExecutor(toolRegistry::dispatch)
                .toolRegistry(toolRegistry)
                .toolSubset(null)
                .postProcessors(List.of(
                        new NoOpPostProcessor(),
                        new MemoryPostProcessor()))
                .terminator(new NoToolCallTerminator())
                .resultCollector(new DirectTextCollector())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private static String switchPersonaTemplate(String personaId) {
        return PromptConstants.textPersonaTemplateName(personaId);
    }

    private static String normalizeTextPersona(String personaId) {
        if (personaId == null) return "chat";
        if ("friendly".equals(personaId) || "concise".equals(personaId)) return personaId;
        return "chat";
    }

    // ── 模型工厂 ──

    private static OpenAiChatModel buildQwenTurbo() {
        return buildModel("qwen-turbo");
    }

    private static OpenAiChatModel buildQwenTurbo(RequestCallRegistry requestCallRegistry) {
        return buildModel("qwen-turbo", requestCallRegistry);
    }

    private static OpenAiChatModel buildQwenFlash() {
        return buildModel("qwen-flash");
    }

    private static OpenAiChatModel buildQwenVlMax() {
        return buildModel("qwen-vl-max");
    }

    private static OpenAiChatModel buildModel(String modelName) {
        return buildModel(modelName, null);
    }

    private static OpenAiChatModel buildModel(String modelName,
                                              RequestCallRegistry requestCallRegistry) {
        OkHttpClientBuilder httpBuilder = OkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(30))
                // 只有带 RequestCallRegistry 的 TEXT 模型进入本阶段统一 30 秒语义；
                // scene/vision 等旧链路保持原有 120 秒 read timeout，避免越界修改。
                .readTimeout(requestCallRegistry != null
                        ? Duration.ofSeconds(30) : Duration.ofSeconds(120));
        if (requestCallRegistry != null) {
            httpBuilder.requestCallRegistry(requestCallRegistry);
        }
        httpBuilder.httpClientBuilder().addInterceptor(new TracingOkHttpInterceptor());
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpBuilder)
                .apiKey(BuildConfig.DASHSCOPE_API_KEY)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName(modelName)
                .parallelToolCalls(true)
                .build();
    }

    // ── 场景工具过滤 ──

    private static final Map<String, Set<String>> SCENE_TOOL_MAP = Map.of(
            "雨雪天气", Set.of("set_window_f_defrosting", "set_window_r_defrosting",
                    "set_seat_pos", "set_seat_heat", "set_steer_heat",
                    "set_ac_temperature", "set_ac_air_volume", "set_ac_mode",
                    "set_ac_circulation", "set_ac_ac", "set_ac_auto",
                    "set_drive_mode"),
            "工程施工", Set.of("set_window_f_defrosting", "set_window_r_defrosting",
                    "set_ac_temperature", "set_ac_ac", "set_ac_circulation"),
            "舱外浓烟", Set.of("set_window_f_defrosting", "set_window_r_defrosting",
                    "set_ac_temperature", "set_ac_ac", "set_ac_circulation",
                    "set_ac_air_volume"),
            "驾驶员疲劳", Set.of("set_frag_switch", "set_frag_level",
                    "set_seat_pos", "set_seat_heat", "set_steer_heat",
                    "set_ac_temperature", "set_ac_air_volume"),
            "乘客休息", Set.of("set_frag_switch", "set_frag_level",
                    "set_window_f_defrosting", "set_window_r_defrosting",
                    "set_ac_temperature", "set_ac_air_volume", "set_ac_mode")
    );

    private static List<ToolSpecification> filterSceneTools(ToolRegistry registry, String sceneName) {
        Set<String> allowed = SCENE_TOOL_MAP.get(sceneName);
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        return registry.getToolSpecifications().stream()
                .filter(spec -> allowed.contains(spec.name()))
                .collect(Collectors.toList());
    }
}
