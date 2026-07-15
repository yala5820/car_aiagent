package com.hirain.aiagent.ai.langchain4j.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具执行器 — 集中化反射派发。
 * <p>
 * 构造时扫描目标对象的所有 {@link Tool @Tool} 方法并建立名称→方法映射。
 * 运行时按 {@link ToolExecutionRequest#name()} 查找对应方法，解析 JSON 参数并反射调用。
 * <p>
 * 参数解析规则：
 * <ol>
 *   <li>优先解析 JSON 中的命名键（与 method parameter name 匹配）</li>
 *   <li>回退到位置参数 {@code arg0}、{@code arg1}…（兼容 qwen 系列模型输出）</li>
 * </ol>
 */
public class ToolDispatcher {

    private static final String TAG = "ToolDispatcher";

    private final Object target;
    private final Map<String, ToolBinding> bindings = new LinkedHashMap<>();

    /** 构造并扫描目标对象的所有 @Tool 方法 */
    public ToolDispatcher(Object target) {
        this.target = target;
        for (Method method : target.getClass().getDeclaredMethods()) {
            Tool toolAnn = method.getAnnotation(Tool.class);
            if (toolAnn == null) continue;
            method.setAccessible(true);

            String toolName = toolAnn.name().isEmpty() ? method.getName() : toolAnn.name();
            Class<?>[] paramTypes = method.getParameterTypes();
            bindings.put(toolName, new ToolBinding(method, paramTypes));
        }
    }

    /** 获取此调度器管理的工具名集合 */
    public Map<String, ToolBinding> getBindings() {
        return bindings;
    }

    /** 根据 ToolExecutionRequest 执行对应工具方法 */
    public String dispatch(ToolExecutionRequest request) {
        return dispatchWithOutcome(request).resultText();
    }

    /**
     * 根据 ToolExecutionRequest 执行对应工具方法，并记录阶段诊断信息。
     * @param diag 可选的诊断收集器（null 表示不收集），调用方传入以获取参数解析/反射调用状态
     */
    public String dispatch(ToolExecutionRequest request, DispatchDiagnostics diag) {
        ToolDispatchOutcome outcome = dispatchWithOutcome(request);
        if (diag != null) {
            diag.argumentParseSuccess = outcome.argumentParseSuccess();
            diag.invokeSuccess = outcome.invokeSuccess();
        }
        return outcome.resultText();
    }

    /** 执行工具并返回不依赖文本推断的结构化结果。 */
    public ToolDispatchOutcome dispatchWithOutcome(ToolExecutionRequest request) {
        if (request == null) {
            return ToolDispatchOutcome.failure(false, false, "无效的工具调用: null",
                    "INVALID_REQUEST", "request is null", null, null);
        }
        ToolBinding binding = bindings.get(request.name());
        if (binding == null) {
            return ToolDispatchOutcome.failure(false, false,
                    "无效的工具调用: " + request.name(), "TOOL_NOT_REGISTERED",
                    "tool is not registered", null, null);
        }
        String targetClass = target.getClass().getSimpleName();
        String targetMethod = binding.method.getName();
        boolean parsed = false;
        try {
            JsonElement parsedElement = JsonParser.parseString(request.arguments());
            if (!parsedElement.isJsonObject()) {
                throw new IllegalArgumentException("工具参数必须是 JSON object");
            }
            JsonObject args = parsedElement.getAsJsonObject();
            Object[] params = resolveParameters(binding, args);
            parsed = true;
            Object result = binding.method.invoke(target, params);
            return ToolDispatchOutcome.success(result != null ? result.toString() : "Success",
                    targetClass, targetMethod);
        } catch (Exception e) {
            Throwable cause = e;
            if (e instanceof InvocationTargetException) {
                cause = e.getCause() != null ? e.getCause() : e;
            }
            String errorType = parsed ? "TOOL_INVOCATION_FAILED" : "ARGUMENT_PARSE_FAILED";
            String detail = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            return ToolDispatchOutcome.failure(true, parsed, "error: 工具执行失败: " + detail,
                    errorType, detail, targetClass, targetMethod);
        }
    }

    // ── 内部实现 ──

    /** 解析方法参数：按 index 从 JSON 中获取 arg0/arg1… */
    private static Object[] resolveParameters(ToolBinding binding, JsonObject args) throws Exception {
        Object[] params = new Object[binding.paramTypes.length];
        for (int i = 0; i < binding.paramTypes.length; i++) {
            Class<?> type = binding.paramTypes[i];
            String key = "arg" + i;

            if (!args.has(key) || args.get(key).isJsonNull()) {
                throw new IllegalArgumentException(
                        "缺少参数: " + key + " (方法: " + binding.method.getName() + ")");
            }

            if (type == boolean.class || type == Boolean.class) {
                params[i] = args.get(key).getAsBoolean();
            } else if (type == int.class || type == Integer.class) {
                params[i] = args.get(key).getAsInt();
            } else {
                params[i] = args.get(key).getAsString();
            }
        }
        return params;
    }

    /**
     * 返回分发目标信息，供 trace 记录。
     * @return "TargetClass.methodName" 或 null（工具未注册）
     */
    public String dispatchTargetInfo(String toolName) {
        ToolBinding b = bindings.get(toolName);
        if (b == null) return null;
        return target.getClass().getSimpleName() + "." + b.method.getName();
    }

    /** 返回工具目标类名。 */
    public String targetClassName(String toolName) {
        ToolBinding b = bindings.get(toolName);
        return b != null ? target.getClass().getSimpleName() : null;
    }

    /** 返回工具目标方法名。 */
    public String targetMethodName(String toolName) {
        ToolBinding b = bindings.get(toolName);
        return b != null ? b.method.getName() : null;
    }

    // ── Dispatch 阶段诊断 ──

    /** 记录 dispatch 各阶段成败的轻量数据容器，供 trace 使用。 */
    public static final class DispatchDiagnostics {
        /** 参数解析是否成功（JSON 解析 + resolveParameters） */
        public boolean argumentParseSuccess;
        /** 反射调用是否成功（method.invoke） */
        public boolean invokeSuccess;
    }

    // ── 内部类型 ──

    static class ToolBinding {
        final Method method;
        final Class<?>[] paramTypes;

        ToolBinding(Method method, Class<?>[] paramTypes) {
            this.method = method;
            this.paramTypes = paramTypes;
        }
    }
}
