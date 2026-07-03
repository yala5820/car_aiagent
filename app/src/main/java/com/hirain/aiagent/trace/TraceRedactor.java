package com.hirain.aiagent.trace;

/**
 * 敏感数据脱敏 — 在写入 Span attribute 前对用户输入、工具参数进行脱敏。
 * <p>
 * 规则：
 * <ul>
 *   <li>图像 Base64 字符串（> 200 字符且以 base64 特征开头）：截断</li>
 *   <li>所有字符串 > 500 字符截断</li>
 *   <li>电话号码：隐藏中间四位</li>
 * </ul>
 */
public class TraceRedactor {

    private static final int INPUT_MAX_LENGTH = 500;
    private static final int ARG_MAX_LENGTH = 200;
    private static final int BASE64_MIN_LENGTH = 200;

    /** 脱敏用户输入文本 */
    public String redactUserInput(String text) {
        if (text == null || text.isEmpty()) return text;
        String redacted = redactPhone(text);
        if (redacted.length() > INPUT_MAX_LENGTH) {
            return redacted.substring(0, INPUT_MAX_LENGTH) + "… (truncated)";
        }
        return redacted;
    }

    /** 脱敏工具参数 JSON */
    public String redactArguments(String jsonArgs) {
        if (jsonArgs == null || jsonArgs.isEmpty()) return jsonArgs;
        // 检测 Base64 图像数据
        if (jsonArgs.length() > BASE64_MIN_LENGTH
                && (jsonArgs.contains("/9j/") || jsonArgs.contains("iVBOR")
                || jsonArgs.contains("base64") || jsonArgs.contains("Base64"))) {
            return jsonArgs.substring(0, 100) + "… (base64 truncated)";
        }
        String redacted = redactPhone(jsonArgs);
        if (redacted.length() > ARG_MAX_LENGTH) {
            return redacted.substring(0, ARG_MAX_LENGTH) + "… (truncated)";
        }
        return redacted;
    }

    /** 脱敏工具执行结果 */
    public String redactResult(String result) {
        if (result == null || result.isEmpty()) return result;
        String redacted = redactPhone(result);
        if (redacted.length() > ARG_MAX_LENGTH) {
            return redacted.substring(0, ARG_MAX_LENGTH) + "… (truncated)";
        }
        return redacted;
    }

    // ── 内部 ──

    private String redactPhone(String text) {
        // 匹配中国大陆手机号（11 位数字，可能前面有 +86）
        return text.replaceAll(
                "((?:\\+86)?1[3-9]\\d)\\d{4}(\\d{4})",
                "$1****$2");
    }
}
