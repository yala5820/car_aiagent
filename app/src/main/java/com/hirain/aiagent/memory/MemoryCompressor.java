package com.hirain.aiagent.memory;

import android.util.Log;

import com.hirain.aiagent.trace.AgentTraceRecorder;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.trace.Span;

/**
 * 记忆压缩器 — Token 额度超限时自动调用 LLM 将旧消息压缩为摘要。
 * <p>
 * 压缩策略：
 * <ul>
 *   <li>触发条件：当前 session token 数 > MAX_TOKENS (4000)</li>
 *   <li>保留规则：SystemMessage 始终保留；最近 COMPRESS_KEEP_LAST 轮不压缩</li>
 *   <li>替换方式：旧消息替换为一条 UserMessage("对话摘要：...")</li>
 * </ul>
 */
public class MemoryCompressor {

    private static final String TAG = "MemoryCompressor";
    private static final int MAX_TOKENS = 4000;
    private static final int TARGET_TOKENS = 2000;
    private static final int COMPRESS_KEEP_LAST = 5;
    private static final String SUMMARY_PREFIX = "【对话摘要】";

    private final ChatModel summaryModel;
    private final String compressionPrompt;

    public MemoryCompressor(ChatModel summaryModel) {
        this.summaryModel = summaryModel;
        this.compressionPrompt = buildCompressionPrompt();
    }

    /**
     * 检查并执行压缩。
     *
     * @param messages       当前消息列表
     * @param currentTokens  当前 Token 估算值
     * @return 压缩后的消息列表（压缩过则新列表，否则原样返回）
     */
    public List<ChatMessage> compress(List<ChatMessage> messages, int currentTokens) {
        return compress(messages, currentTokens, null);
    }

    /**
     * 检查并执行压缩，同时记录压缩决策 trace。
     *
     * @param messages       当前消息列表
     * @param currentTokens  当前 Token 估算值
     * @param trace          当前 Agent trace recorder；为 null 时保持原有无 trace 行为
     * @return 压缩后的消息列表（压缩过则新列表，否则原样返回）
     */
    /**
     * 生成压缩计划 — 确定哪些消息可以压缩，但不调用摘要模型。
     * 返回提议消息列表：保留最近 COMPRESS_KEEP_LAST 轮 + SystemMessage。
     */
    public List<ChatMessage> planCompact(List<ChatMessage> messages, int targetTokens) {
        if (messages == null || messages.size() <= COMPRESS_KEEP_LAST + 2) {
            return new ArrayList<>(messages != null ? messages : List.of());
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                result.add(msg);
                break;
            }
        }
        int keepStart = Math.max(messages.size() - COMPRESS_KEEP_LAST * 2, 0);
        for (int i = keepStart; i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        return result;
    }

    public List<ChatMessage> compress(List<ChatMessage> messages, int currentTokens,
                                      AgentTraceRecorder trace) {
        Span span = trace != null
                ? trace.startMemory("compress", inputChars(messages))
                : null;
        try {
        if (currentTokens < MAX_TOKENS || messages.size() <= COMPRESS_KEEP_LAST + 2) {
            finishCompress(trace, span, false, "", "");
            return messages; // 无需压缩
        }

        // 分离 SystemMessage 和对话消息
        SystemMessage systemMsg = null;
        List<ChatMessage> dialogMessages = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage && systemMsg == null) {
                systemMsg = (SystemMessage) msg;
            } else {
                dialogMessages.add(msg);
            }
        }

        // 最近 N 轮保留不压缩
        int keepCount = Math.min(COMPRESS_KEEP_LAST * 2, dialogMessages.size());
        List<ChatMessage> keepMessages = dialogMessages.subList(
                dialogMessages.size() - keepCount, dialogMessages.size());
        List<ChatMessage> compressMessages = dialogMessages.subList(
                0, dialogMessages.size() - keepCount);

        if (compressMessages.isEmpty()) {
            finishCompress(trace, span, false, "", "");
            return messages;
        }

        // 检查压缩区域是否有未完成的工具调用
        if (hasPendingToolCall(compressMessages)) {
            safeLogD("Deferring compression: pending tool calls in target region");
            finishCompress(trace, span, false, "", "");
            return messages;
        }

        // 执行 LLM 摘要
        SummaryResult summaryResult = summarize(compressMessages);
        if (summaryResult.error != null) {
            if (trace != null) {
                trace.recordException(span, summaryResult.error);
            }
            finishCompress(trace, span, false, summaryResult.prompt, "");
            return messages;
        }
        if (summaryResult.summary.isEmpty()) {
            safeLogW("Compression returned empty summary, skipping", null);
            finishCompress(trace, span, false, summaryResult.prompt, summaryResult.summary);
            return messages;
        }

        // 组装新消息列表: SystemMessage + 摘要 + 保留的最近消息
        List<ChatMessage> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);
        result.add(UserMessage.from(SUMMARY_PREFIX + summaryResult.summary));
        result.addAll(keepMessages);

        finishCompress(trace, span, true, summaryResult.prompt, summaryResult.summary);
        safeLogD(String.format("Compressed %d messages to summary + %d recent messages (tokens: %d→~%d)",
                compressMessages.size(), keepCount, currentTokens, TARGET_TOKENS));
        return result;
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    // ── 内部方法 ──

    private SummaryResult summarize(List<ChatMessage> messages) {
        StringBuilder dialogText = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage) {
                UserMessage um = (UserMessage) msg;
                dialogText.append("用户：").append(um.singleText()).append("\n");
            } else if (msg instanceof AiMessage) {
                AiMessage am = (AiMessage) msg;
                if (am.text() != null && !am.text().isEmpty()) {
                    dialogText.append("AI：").append(am.text()).append("\n");
                } else if (am.hasToolExecutionRequests()) {
                    dialogText.append("AI：调用了工具 ").append(
                            am.toolExecutionRequests().get(0).name()).append("\n");
                }
            } else if (msg instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage tr = (ToolExecutionResultMessage) msg;
                String abbrev = tr.text() != null && tr.text().length() > 80
                        ? tr.text().substring(0, 80) + "…" : tr.text();
                dialogText.append("工具[").append(tr.toolName()).append("]：").append(abbrev).append("\n");
            }
        }

        String prompt = compressionPrompt.replace("{{messages}}", dialogText.toString());
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
        try {
            ChatResponse response = summaryModel.chat(request);
            String summary = response != null
                    && response.aiMessage() != null
                    && response.aiMessage().text() != null
                    ? response.aiMessage().text().trim()
                    : "";
            return new SummaryResult(prompt, summary, null);
        } catch (Exception e) {
            safeLogE("Compression LLM call failed", e);
            return new SummaryResult(prompt, "", e);
        }
    }

    private boolean hasPendingToolCall(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                AiMessage am = (AiMessage) messages.get(i);
                return am.hasToolExecutionRequests();
            }
        }
        return false;
    }

    private static void finishCompress(AgentTraceRecorder trace, Span span, boolean compressed,
                                       String prompt, String summary) {
        if (trace != null) {
            trace.finishMemoryCompress(span, compressed, prompt, summary);
        }
    }

    private static int inputChars(List<ChatMessage> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                total += sm.text() != null ? sm.text().length() : 0;
            } else if (msg instanceof UserMessage um && um.hasSingleText()) {
                total += um.singleText() != null ? um.singleText().length() : 0;
            } else if (msg instanceof AiMessage am) {
                total += am.text() != null ? am.text().length() : 0;
            } else if (msg instanceof ToolExecutionResultMessage tr && tr.hasSingleText()) {
                total += tr.text() != null ? tr.text().length() : 0;
            } else if (msg != null) {
                total += msg.toString().length();
            }
        }
        return total;
    }

    private static void safeLogD(String message) {
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响记忆主流程和 trace 断言。
        }
    }

    private static void safeLogW(String message, Throwable throwable) {
        try {
            if (throwable != null) {
                Log.w(TAG, message, throwable);
            } else {
                Log.w(TAG, message);
            }
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响记忆主流程和 trace 断言。
        }
    }

    private static void safeLogE(String message, Throwable throwable) {
        try {
            Log.e(TAG, message, throwable);
        } catch (RuntimeException ignored) {
            // JVM 单测环境没有 Android Log 实现，日志失败不能影响记忆主流程和 trace 断言。
        }
    }

    private static final class SummaryResult {
        private final String prompt;
        private final String summary;
        private final Exception error;

        private SummaryResult(String prompt, String summary, Exception error) {
            this.prompt = prompt != null ? prompt : "";
            this.summary = summary != null ? summary : "";
            this.error = error;
        }
    }

    private static String buildCompressionPrompt() {
        return "请对以下对话历史进行摘要，保留所有关键信息：\n" +
                "- 用户的偏好和要求\n" +
                "- AI 已经执行的操作和结果\n" +
                "- 未解决的问题\n" +
                "- 重要的上下文（位置、时间、车辆状态等）\n" +
                "\n对话历史：\n{{messages}}\n\n" +
                "摘要（请用中文，控制在 500 字以内）：";
    }
}
