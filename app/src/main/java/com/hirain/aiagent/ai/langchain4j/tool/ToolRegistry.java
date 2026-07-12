package com.hirain.aiagent.ai.langchain4j.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

/**
 * 工具注册中心 — 集中管理所有工具管理器的注册与派发。
 * <p>
 * 职责：
 * <ul>
 *   <li>注册所有工具管理器实例（registerAll）</li>
 *   <li>自动收集所有 {@link ToolSpecification} 供 LLM 使用</li>
 *   <li>按工具名称路由 {@link ToolExecutionRequest} 到对应的管理器执行</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * ToolRegistry registry = new ToolRegistry();
 * registry.registerAll(
 *     new VehicleDoorManager(),
 *     new VehicleWindowManager(),
 *     new WeatherUtils(apiKey)
 * );
 * List<ToolSpecification> specs = registry.getToolSpecifications();
 * String result = registry.dispatch(request);
 * }</pre>
 */
public class ToolRegistry {

    /** toolName → ToolDispatcher 映射 */
    private final Map<String, ToolDispatcher> dispatchers = new LinkedHashMap<>();
    /** 完整的工具规格列表（按注册顺序） */
    private List<ToolSpecification> allSpecs = List.of();

    /** 注册一个或多个工具管理器。拒绝同名工具覆盖。 */
    public void registerAll(Object... managers) {
        List<ToolSpecification> specs = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (Object mgr : managers) {
            ToolDispatcher dispatcher = new ToolDispatcher(mgr);
            List<ToolSpecification> mgrSpecs =
                    ToolSpecifications.toolSpecificationsFrom(mgr.getClass());
            for (ToolSpecification spec : mgrSpecs) {
                if (!seenNames.add(spec.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate tool name: " + spec.name());
                }
                dispatchers.put(spec.name(), dispatcher);
            }
            specs.addAll(mgrSpecs);
        }
        allSpecs = List.copyOf(specs);
    }

    /**
     * 按名称查询一组工具规格，保持输入顺序。
     *
     * @param names 工具名称列表（去重保留顺序）
     * @return 对应的 ToolSpecification 列表
     * @throws ToolSpecNotFoundException 任一名称不存在时抛出
     */
    public List<ToolSpecification> toolSpecificationsByNames(List<String> names) {
        List<ToolSpecification> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && seen.add(name)) {
                    ToolDispatcher d = dispatchers.get(name);
                    if (d == null) {
                        throw new ToolSpecNotFoundException(name);
                    }
                    // Find the matching spec from allSpecs
                    ToolSpecification match = null;
                    for (ToolSpecification spec : allSpecs) {
                        if (name.equals(spec.name())) {
                            match = spec;
                            break;
                        }
                    }
                    if (match != null) {
                        result.add(match);
                    } else {
                        throw new ToolSpecNotFoundException(name);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 返回全部已启用工具规格（Demo 阶段等价于全量）。
     * 仅供已经确认 {@code allToolsFallback=true} 的分支使用。
     */
    public List<ToolSpecification> enabledToolSpecifications() {
        return new ArrayList<>(allSpecs);
    }

    /**
     * 派发一个工具执行请求到对应的管理器。
     *
     * @param request LLM 返回的工具调用请求
     * @return 工具执行结果字符串（JSON 格式或普通文本）
     */
    public String dispatch(ToolExecutionRequest request) {
        ToolDispatcher dispatcher = dispatchers.get(request.name());
        if (dispatcher == null) {
            return "无效的工具调用: " + request.name();
        }
        return dispatcher.dispatch(request);
    }

    /**
     * 获取所有已注册工具的规格声明列表。
     * 供 ChatRequest.Builder.toolSpecifications() 使用。
     */
    public List<ToolSpecification> getToolSpecifications() {
        return allSpecs;
    }

    /** 获取当前注册的工具数量 */
    public int size() {
        return allSpecs.size();
    }
}
