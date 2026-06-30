package com.hirain.aiagent.prompt;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.langchain4j.model.input.PromptTemplate;

/**
 * Prompt 模板管理器 — 从 Android assets 加载、缓存并渲染 Prompt 模板。
 * <p>
 * 模板文件存放在 {@code assets/prompts/} 目录下，使用 LangChain4j
 * {@link PromptTemplate#from(String)} + {@code {{variable}}} 语法。
 * <p>
 * 线程安全：首次 {@link #render(String, Map)} 时懒加载并缓存，
 * 后续直接命中缓存。
 */
public class PromptManager {

    private static final String TAG = "PromptManager";
    private static final String PROMPT_BASE = "prompts/";

    private final Context context;
    private final ConcurrentMap<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 渲染指定名称的 Prompt 模板。
     *
     * @param templateName 模板名称（相对于 assets/prompts/，不含 .txt 后缀）
     * @param variables    模板变量
     * @return 渲染后的文本
     * @throws IllegalArgumentException 模板文件不存在或读取失败
     */
    public String render(String templateName, Map<String, Object> variables) {
        PromptTemplate template = cache.get(templateName);
        if (template == null) {
            template = loadTemplate(templateName);
            PromptTemplate existing = cache.putIfAbsent(templateName, template);
            if (existing != null) {
                template = existing;
            }
        }
        return template.apply(variables).text();
    }

    /** 渲染不带变量的模板 */
    public String render(String templateName) {
        return render(templateName, Map.of());
    }

    /**
     * 清除缓存并重新加载所有模板（用于热加载场景）。
     */
    public void reload() {
        cache.clear();
    }

    // ── 内部实现 ──

    private PromptTemplate loadTemplate(String templateName) {
        String filePath = PROMPT_BASE + templateName + ".txt";
        try (InputStream is = context.getAssets().open(filePath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            String content = out.toString(StandardCharsets.UTF_8.name());
            Log.d(TAG, "Loaded template: " + filePath + " (" + content.length() + " chars)");
            return PromptTemplate.from(content);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Prompt template not found: " + filePath, e);
        }
    }
}
