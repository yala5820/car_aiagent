package com.hirain.aiagent.ai.langchain4j.tool;

import android.util.Log;

import org.json.JSONObject;

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
        Log.d(TAG, "Registered " + bindings.size() + " tools for "
                + target.getClass().getSimpleName());
    }

    /** 获取此调度器管理的工具名集合 */
    public Map<String, ToolBinding> getBindings() {
        return bindings;
    }

    /** 根据 ToolExecutionRequest 执行对应工具方法 */
    public String dispatch(ToolExecutionRequest request) {
        return dispatch(request, null);
    }

    /**
     * 根据 ToolExecutionRequest 执行对应工具方法，并记录阶段诊断信息。
     * @param diag 可选的诊断收集器（null 表示不收集），调用方传入以获取参数解析/反射调用状态
     */
    public String dispatch(ToolExecutionRequest request, DispatchDiagnostics diag) {
        ToolBinding binding = bindings.get(request.name());
        if (binding == null) {
            return "无效的工具调用: " + request.name();
        }
        try {
            JSONObject args = new JSONObject(request.arguments());
            Object[] params = resolveParameters(binding, args);
            if (diag != null) diag.argumentParseSuccess = true;

            Object result = binding.method.invoke(target, params);
            if (diag != null) diag.invokeSuccess = true;
            return result != null ? result.toString() : "Success";
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute tool: " + request.name(), e);
            Throwable cause = e;
            if (e instanceof InvocationTargetException) {
                cause = e.getCause();
                // 反射调用本身失败，参数解析是成功的（已越过 parse）
                if (diag != null && !diag.argumentParseSuccess) diag.argumentParseSuccess = true;
            } else if (diag != null) {
                // 参数解析阶段失败
                diag.invokeSuccess = false;
                diag.argumentParseSuccess = false;
            }
            return "工具执行失败: " + cause.getMessage();
        }
    }

    // ── 内部实现 ──

    /** 解析方法参数：按 index 从 JSON 中获取 arg0/arg1… */
    private static Object[] resolveParameters(ToolBinding binding, JSONObject args) throws Exception {
        Object[] params = new Object[binding.paramTypes.length];
        for (int i = 0; i < binding.paramTypes.length; i++) {
            Class<?> type = binding.paramTypes[i];
            String key = "arg" + i;

            if (!args.has(key)) {
                throw new IllegalArgumentException(
                        "缺少参数: " + key + " (方法: " + binding.method.getName() + ")");
            }

            if (type == boolean.class || type == Boolean.class) {
                params[i] = args.getBoolean(key);
            } else if (type == int.class || type == Integer.class) {
                params[i] = args.getInt(key);
            } else {
                params[i] = args.getString(key);
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
