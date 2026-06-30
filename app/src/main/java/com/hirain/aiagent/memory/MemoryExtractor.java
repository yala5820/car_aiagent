package com.hirain.aiagent.memory;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

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
        if (userMessage == null || userMessage.isEmpty()) return List.of();

        String prompt = extractPrompt
                .replace("{{user_message}}", userMessage)
                .replace("{{ai_response}}", aiResponse != null ? aiResponse : "");

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build();
            ChatResponse response = extractModel.chat(request);
            String text = response.aiMessage().text().trim();

            return parseCandidates(text);
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            return List.of();
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
            JSONArray arr = new JSONArray(clean);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                MemoryEntry.Category category = MemoryEntry.Category.from(
                        obj.optString("category", "fact"));
                String key = obj.optString("key", "");
                String value = obj.optString("value", "");
                double confidence = obj.optDouble("confidence", 0.5);

                if (!key.isEmpty() && !value.isEmpty() && confidence >= 0.3) {
                    candidates.add(new MemoryCandidate(
                            category, key, value, (float) confidence));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse extraction result: " + clean.substring(
                    Math.min(0, clean.length()), Math.min(100, clean.length())), e);
        }

        return candidates;
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
