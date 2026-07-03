package com.hirain.aiagent.memory;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.trace.AgentTraceRecorder;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.trace.Span;

/**
 * 记忆提取器 — 在每轮对话结束后分析用户消息和 AI 响应，
 * 提取可写入长期记忆的用户偏好、事实、习惯和规则。
 * <p>
 * 使用结构化输出 Prompt 让 LLM 输出 JSON 数组。
 */
public class MemoryExtractor {

    private static final String TAG = "MemoryExtractor";

    private final ChatModel extractModel;
    private final String extractPrompt;

    public MemoryExtractor(ChatModel extractModel) {
        this.extractModel = extractModel;
        this.extractPrompt = buildExtractPrompt();
    }

    /**
     * 从一轮对话中提取可记忆的信息。
     *
     * @param userMessage 用户本轮输入
     * @param aiResponse  AI 本轮输出
     * @return 提取结果列表（可能为空）
     */
    public List<MemoryCandidate> extract(String userMessage, String aiResponse) {
        return extract(userMessage, aiResponse, null);
    }

    /**
     * 从一轮对话中提取可记忆的信息，并在当前请求 trace 下记录提取 prompt 与模型原始输出。
     *
     * @param userMessage 用户本轮输入
     * @param aiResponse  AI 本轮输出
     * @param trace       当前 Agent trace recorder；为 null 时保持原有无 trace 行为
     * @return 提取结果列表（可能为空）
     */
    public List<MemoryCandidate> extract(String userMessage, String aiResponse, AgentTraceRecorder trace) {
        if (userMessage == null || userMessage.isEmpty()) return List.of();

        String prompt = extractPrompt
                .replace("{{user_message}}", userMessage)
                .replace("{{ai_response}}", aiResponse != null ? aiResponse : "");
        Span span = trace != null
                ? trace.startMemory("extract", inputChars(userMessage, aiResponse))
                : null;

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build();
            ChatResponse response = extractModel.chat(request);
            String text = response != null
                    && response.aiMessage() != null
                    && response.aiMessage().text() != null
                    ? response.aiMessage().text().trim()
                    : "";

            List<MemoryCandidate> candidates = parseCandidates(text);
            if (trace != null) {
                trace.finishMemoryExtract(span, prompt, text, candidates.size());
            }
            return candidates;
        } catch (Exception e) {
            if (trace != null) {
                trace.recordException(span, e);
            }
            safeLogE("Extraction failed", e);
            return List.of();
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    // ── 内部 ──

    private List<MemoryCandidate> parseCandidates(String jsonText) {
        List<MemoryCandidate> candidates = new ArrayList<>();

        // 去除可能的 Markdown 代码块标记
        String clean = jsonText.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        try {
            JsonElement root = JsonParser.parseString(clean);
            if (!root.isJsonArray()) {
                return candidates;
            }
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement item : arr) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                MemoryEntry.Category category = MemoryEntry.Category.from(
                        optString(obj, "category", "fact"));
                String key = optString(obj, "key", "");
                String value = optString(obj, "value", "");
                double confidence = optDouble(obj, "confidence", 0.5);

                if (!key.isEmpty() && !value.isEmpty() && confidence >= 0.3) {
                    candidates.add(new MemoryCandidate(
                            category, key, value, (float) confidence));
                }
            }
        } catch (Exception e) {
            safeLogW("Failed to parse extraction result: " + clean.substring(
                    Math.min(0, clean.length()), Math.min(100, clean.length())), e);
        }

        return candidates;
    }

    private static String optString(JsonObject obj, String key, String defaultValue) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return defaultValue;
        try {
            return value.getAsString();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static double optDouble(JsonObject obj, String key, double defaultValue) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return defaultValue;
        try {
            return value.getAsDouble();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static int inputChars(String userMessage, String aiResponse) {
        return (userMessage != null ? userMessage.length() : 0)
                + (aiResponse != null ? aiResponse.length() : 0);
    }

    private static void safeLogE(String message, Throwable throwable) {
        try {
            Log.e(TAG, message, throwable);
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响记忆主流程和 trace 断言。
        }
    }

    private static void safeLogW(String message, Throwable throwable) {
        try {
            Log.w(TAG, message, throwable);
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响记忆主流程和 trace 断言。
        }
    }

    private static String buildExtractPrompt() {
        return "分析以下对话，提取值得长期记忆的信息。只提取明确表达的信息，不要推测。\n" +
                "\n需提取的类型：\n" +
                "- preference: 用户的明确偏好（\"我喜欢…\"、\"调高一点\"）\n" +
                "- fact: 用户陈述的事实（\"我在去机场的路上\"）\n" +
                "- habit: 用户的习惯模式（同类操作出现多次）\n" +
                "- rule: 用户明确要求的规则（\"永远不要在我开车时说话\"）\n" +
                "\n对话：\n用户：{{user_message}}\nAI：{{ai_response}}\n" +
                "\n输出 JSON 数组（可为空数组）：\n" +
                "[{\"category\": \"...\", \"key\": \"...\", \"value\": \"...\", \"confidence\": 0.0-1.0}]";
    }
}
