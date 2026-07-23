package com.hirain.aiagent.rag.indexer.chunk;

/**
 * V1 版本化估算口径：CJK 字符按 1，连续非 CJK 文本约每 4 字符 1 Token；用于预算，非模型精确 Tokenizer。
 */
public final class TokenEstimator {
    public static final int VERSION = 1;

    public int estimate(String text) {
        int tokens = 0;
        int latinRun = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN) {
                tokens++;
                latinRun = 0;
            } else if (!Character.isWhitespace(value)) {
                latinRun++;
                if (latinRun % 4 == 1) {
                    tokens++;
                }
            }
        }
        return tokens;
    }
}
