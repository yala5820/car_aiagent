package com.hirain.aiagent.memory;

/**
 * 共享短期会话中的发言人标记工具。
 * <p>
 * 设计原因：座舱内同一个 session 可能有多个发言人，短期历史必须保留 speaker，
 * 否则模型无法判断历史消息中的"我"属于哪个用户。
 */
public final class SpeakerMessageFormatter {
    private static final String DEFAULT_USER_ID = "default_user";

    private SpeakerMessageFormatter() {}

    /**
     * 格式化用户消息，添加 speaker 前缀。
     * <p>
     * 格式：{@code [speaker={userId}] {text}}
     * 空白 userId 降级为 {@code default_user}，null text 降级为空字符串。
     */
    public static String formatUserMessage(String userId, String text) {
        String safeUserId = isBlank(userId) ? DEFAULT_USER_ID : userId.trim();
        String safeText = text != null ? text : "";
        return "[speaker=" + safeUserId + "] " + safeText;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
